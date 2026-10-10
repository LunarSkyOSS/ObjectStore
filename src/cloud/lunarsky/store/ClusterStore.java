package cloud.lunarsky.store;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.DigestInputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class ClusterStore implements ObjectStorage {
    private record Segment(UUID id, int length, byte[] hash, List<UUID> replicas) {}
    private record RepairTarget(UUID generation, int ordinal, long version, Segment segment) {}
    record RepairReport(int scanned, int restored, int underReplicated, int unrecoverable) {}
    private final String jdbcUrl, user, password, configuredBucket;
    private final NodeClient nodes;
    private final long maxObject, maxTotal;
    private final boolean testNodeDomains;

    ClusterStore(String jdbcUrl, String user, String password, String bucket,
                 List<URI> nodeUrls, String token, String repairToken, long maxObject, long maxTotal,
                 boolean testNodeDomains) throws IOException {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://") || user == null || password == null)
            throw new IllegalArgumentException("Invalid metadata database configuration");
        this.jdbcUrl = jdbcUrl; this.user = user; this.password = password;
        this.configuredBucket = bucket; this.maxObject = maxObject; this.maxTotal = maxTotal;
        this.testNodeDomains = testNodeDomains;
        try (Connection connection = connect()) {
            int format = SchemaMigrator.prepare(connection, bucket);
            if (format != 2) throw new IOException("Legacy replica positions require objectstore cluster-migrate before this gateway can start");
            nodes = NodeRegistry.load(connection, nodeUrls, token, repairToken);
        } catch (SQLException error) { throw databaseError(error); }
    }

    private Connection connect() throws SQLException { return DriverManager.getConnection(jdbcUrl, user, password); }

    @Override public Metadata put(String bucket, String key, InputStream input, long length, String expectedHash,
                                  String checksum, boolean createOnly, String contentType) throws IOException {
        validatePut(bucket, length, contentType);
        MessageDigest md5 = digest("MD5");
        Path staged = Files.createTempFile("objectstore-cluster-", ".pending");
        List<Segment> segments;
        byte[] fullHash;
        try {
            fullHash = stageInput(staged, input, length, expectedHash, checksum, md5);
            checkCapacity(bucket, key, length, createOnly);
            segments = uploadSegments(staged, length);
        } finally { Files.deleteIfExists(staged); }
        Metadata metadata = new Metadata(length, Instant.now().toEpochMilli(),
            HexFormat.of().formatHex(md5.digest()), fullHash, bucket, key, contentType);
        persistObject(metadata, segments, createOnly);
        return metadata;
    }

    private void validatePut(String bucket, long length, String contentType) {
        if (!configuredBucket.equals(bucket)) throw new StoreException(404, "NoSuchBucket", "Bucket not found");
        if (length < 0) throw new StoreException(411, "MissingContentLength", "Content-Length is required");
        if (length > maxObject) throw new StoreException(413, "EntityTooLarge", "Object exceeds the configured size limit");
        if (!nodes.availableHostsAtLeast(2, testNodeDomains))
            throw new StoreException(503, "SlowDown", "Fewer than two storage hosts are available");
        if (contentType.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255)
            throw new StoreException(400, "InvalidArgument", "Content-Type is too long");
    }

    private byte[] stageInput(Path staged, InputStream input, long length, String expectedHash,
                              String checksum, MessageDigest md5) throws IOException {
        MessageDigest sha = digest("SHA-256");
        try (OutputStream output = Files.newOutputStream(staged)) {
            byte[] buffer = new byte[65536];
            long remaining = length;
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new StoreException(400, "IncompleteBody", "Payload length does not match Content-Length");
                if (count == 0) continue;
                sha.update(buffer, 0, count); md5.update(buffer, 0, count);
                output.write(buffer, 0, count);
                remaining -= count;
            }
        }
        if (input.read() != -1) throw new StoreException(413, "EntityTooLarge", "Payload exceeds declared size");
        byte[] fullHash = sha.digest();
        if (!HexFormat.of().formatHex(fullHash).equals(expectedHash))
            throw new StoreException(400, "XAmzContentSHA256Mismatch", "Payload hash mismatch");
        if (checksum != null && !Base64.getEncoder().encodeToString(fullHash).equals(checksum))
            throw new StoreException(400, "BadDigest", "SHA-256 checksum mismatch");
        return fullHash;
    }

    private void checkCapacity(String bucket, String key, long length, boolean createOnly) throws IOException {
        try (Connection connection = connect()) {
            long previous = currentLength(connection, bucket, key);
            if (createOnly && previous >= 0)
                throw new StoreException(412, "PreconditionFailed", "Object already exists");
            try (PreparedStatement query = connection.prepareStatement("SELECT used_bytes FROM cluster_usage WHERE bucket=?")) {
                query.setString(1, bucket);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) throw new SQLException("Bucket quota row is missing");
                    if (result.getLong(1) - Math.max(0, previous) > maxTotal - length)
                        throw new StoreException(507, "InsufficientStorage", "Store capacity limit reached");
                }
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    private List<Segment> uploadSegments(Path staged, long length) throws IOException {
        List<Segment> segments = new ArrayList<>();
        try (InputStream stagedInput = Files.newInputStream(staged)) {
            long remaining = length;
            while (remaining > 0) {
                int wanted = (int) Math.min(ClusterNode.MAX_SEGMENT, remaining);
                byte[] bytes = stagedInput.readNBytes(wanted);
                if (bytes.length != wanted) throw new IOException("Staged object was truncated");
                byte[] segmentHash = SigV4.hash(bytes);
                UUID id = UUID.randomUUID();
                List<UUID> replicas = new ArrayList<>();
                Set<UUID> acceptedHosts = new HashSet<>();
                for (int index : PlacementPolicy.candidates(id, nodes, testNodeDomains)) {
                    UUID host = nodes.faultDomain(index, testNodeDomains);
                    if (acceptedHosts.contains(host)) continue;
                    try {
                        nodes.put(index, id, bytes, segmentHash);
                        replicas.add(nodes.node(index).id());
                        acceptedHosts.add(host);
                        if (acceptedHosts.size() == 3) break;
                    } catch (IOException error) {
                        System.err.println("Cluster node " + nodes.node(index).id() +
                            " did not accept segment " + id + ": " + error.getMessage());
                    }
                }
                if (acceptedHosts.size() < 2)
                    throw new StoreException(503, "SlowDown", "Fewer than two storage hosts accepted the segment");
                segments.add(new Segment(id, wanted, segmentHash, List.copyOf(replicas)));
                remaining -= wanted;
            }
        }
        return segments;
    }

    private void persistObject(Metadata metadata, List<Segment> segments, boolean createOnly) throws IOException {
        String bucket = metadata.bucket(), key = metadata.key();
        long length = metadata.length();
        UUID generation = UUID.randomUUID();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                long used = lockUsage(connection, bucket);
                long previous = currentLength(connection, bucket, key);
                if (createOnly && previous >= 0)
                    throw new StoreException(412, "PreconditionFailed", "Object already exists");
                if (used - Math.max(0, previous) > maxTotal - length)
                    throw new StoreException(507, "InsufficientStorage", "Store capacity limit reached");
                try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cluster_segments (generation, ordinal, segment_id, length, sha256, replicas, replica_ids) VALUES (?, ?, ?, ?, ?, 'v2', ?)")) {
                    for (int i = 0; i < segments.size(); i++) {
                        Segment segment = segments.get(i);
                        insert.setObject(1, generation); insert.setInt(2, i); insert.setObject(3, segment.id());
                        insert.setInt(4, segment.length()); insert.setBytes(5, segment.hash());
                        insert.setArray(6, connection.createArrayOf("uuid", segment.replicas().toArray()));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                try (PreparedStatement update = connection.prepareStatement(
                    "INSERT INTO cluster_objects VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (bucket, object_key) DO UPDATE SET generation=EXCLUDED.generation, length=EXCLUDED.length, modified=EXCLUDED.modified, etag=EXCLUDED.etag, sha256=EXCLUDED.sha256, content_type=EXCLUDED.content_type")) {
                    bindObject(update, metadata, generation); update.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement("UPDATE cluster_usage SET used_bytes=? WHERE bucket=?")) {
                    update.setLong(1, used - Math.max(0, previous) + length); update.setString(2, bucket); update.executeUpdate();
                }
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM cluster_tombstones WHERE bucket=? AND object_key=?")) {
                    delete.setString(1, bucket); delete.setString(2, key); delete.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw databaseError(sql);
                throw error;
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    @Override public OpenObject open(String bucket, String key) throws IOException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                Metadata metadata;
                UUID generation;
                try (PreparedStatement query = connection.prepareStatement(
                    "SELECT generation, length, modified, etag, sha256, content_type FROM cluster_objects WHERE bucket=? AND object_key=?")) {
                    query.setString(1, bucket); query.setString(2, key);
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) throw new StoreException(404, "NoSuchKey", "Object not found");
                        generation = (UUID) result.getObject(1);
                        metadata = new Metadata(result.getLong(2), result.getLong(3), result.getString(4),
                            result.getBytes(5), bucket, key, result.getString(6));
                    }
                }
                List<Segment> parts = new ArrayList<>();
                try (PreparedStatement query = connection.prepareStatement(
                    "SELECT segment_id, length, sha256, replica_ids FROM cluster_segments WHERE generation=? ORDER BY ordinal")) {
                    query.setObject(1, generation);
                    try (ResultSet result = query.executeQuery()) {
                        long total = 0;
                        while (result.next()) {
                            Segment segment = new Segment((UUID) result.getObject(1), result.getInt(2),
                                result.getBytes(3), replicaIds(result, 4));
                            total = Math.addExact(total, segment.length());
                            parts.add(segment);
                        }
                        if (total != metadata.length()) throw new IOException("Incomplete object manifest");
                    }
                }
                connection.commit();
                return new OpenObject(metadata, verifiedObject(parts, metadata));
            } catch (SQLException | RuntimeException | IOException error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw databaseError(sql);
                if (error instanceof IOException io) throw io;
                throw error;
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    @Override public void delete(String bucket, String key) throws IOException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                long used = lockUsage(connection, bucket);
                long previous = currentLength(connection, bucket, key);
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM cluster_objects WHERE bucket=? AND object_key=?")) {
                    delete.setString(1, bucket); delete.setString(2, key); delete.executeUpdate();
                }
                try (PreparedStatement update = connection.prepareStatement(
                    "INSERT INTO cluster_tombstones VALUES (?, ?, ?, ?) ON CONFLICT (bucket, object_key) DO UPDATE SET generation=EXCLUDED.generation, deleted_at=EXCLUDED.deleted_at")) {
                    update.setString(1, bucket); update.setString(2, key);
                    update.setObject(3, UUID.randomUUID()); update.setLong(4, Instant.now().toEpochMilli());
                    update.executeUpdate();
                }
                if (previous >= 0) {
                    try (PreparedStatement update = connection.prepareStatement("UPDATE cluster_usage SET used_bytes=? WHERE bucket=?")) {
                        update.setLong(1, used - previous); update.setString(2, bucket); update.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw databaseError(sql);
                throw error;
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    @Override public ListPage list(String bucket, String prefix, String delimiter, int maxKeys, String after) throws IOException {
        if (maxKeys == 0) return new ListPage(new ArrayList<>(), new ArrayList<>(), null, false);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            ListPage page;
            try (PreparedStatement query = connection.prepareStatement(
                "SELECT object_key, length, modified, etag, sha256, content_type FROM cluster_objects WHERE bucket=? AND object_key>=? ORDER BY object_key")) {
                query.setString(1, bucket);
                query.setString(2, after != null && after.compareTo(prefix) > 0 ? after : prefix);
                query.setFetchSize(128);
                try (ResultSet result = query.executeQuery()) {
                    page = readListPage(result, bucket, prefix, delimiter, maxKeys, after);
                }
            }
            connection.commit();
            return page;
        } catch (SQLException error) { throw databaseError(error); }
    }

    private static ListPage readListPage(ResultSet result, String bucket, String prefix, String delimiter,
                                         int maxKeys, String after) throws SQLException {
        List<ListedObject> entries = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        String lastKey = null, activePrefix = null;
        boolean truncated = false;
        while (result.next()) {
            String key = result.getString(1);
            if (!key.startsWith(prefix)) break;
            if (after != null && key.compareTo(after) <= 0) continue;
            String group = commonPrefix(key, prefix, delimiter);
            if (group != null && group.equals(activePrefix)) { lastKey = key; continue; }
            if (entries.size() + prefixes.size() >= maxKeys) { truncated = true; break; }
            if (group != null) { prefixes.add(group); activePrefix = group; }
            else {
                entries.add(new ListedObject(key, new Metadata(result.getLong(2), result.getLong(3),
                    result.getString(4), result.getBytes(5), bucket, key, result.getString(6))));
                activePrefix = null;
            }
            lastKey = key;
        }
        return new ListPage(entries, prefixes, truncated ? lastKey : null, truncated);
    }

    private static String commonPrefix(String key, String prefix, String delimiter) {
        if (delimiter.isEmpty()) return null;
        int at = key.indexOf(delimiter, prefix.length());
        return at < 0 ? null : key.substring(0, at + delimiter.length());
    }

    private long lockUsage(Connection connection, String bucket) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT used_bytes FROM cluster_usage WHERE bucket=? FOR UPDATE")) {
            query.setString(1, bucket);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new SQLException("Bucket quota row is missing");
                return result.getLong(1);
            }
        }
    }
    private long currentLength(Connection connection, String bucket, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT length FROM cluster_objects WHERE bucket=? AND object_key=?")) {
            query.setString(1, bucket); query.setString(2, key);
            try (ResultSet result = query.executeQuery()) { return result.next() ? result.getLong(1) : -1; }
        }
    }
    private static void bindObject(PreparedStatement update, Metadata data, UUID generation) throws SQLException {
        update.setString(1, data.bucket()); update.setString(2, data.key()); update.setObject(3, generation);
        update.setLong(4, data.length()); update.setLong(5, data.modified()); update.setString(6, data.etag());
        update.setBytes(7, data.sha256()); update.setString(8, data.contentType());
    }
    private static List<UUID> replicaIds(ResultSet result, int column) throws SQLException, IOException {
        java.sql.Array value = result.getArray(column);
        if (value == null) throw new IOException("Segment has no migrated replica identities");
        try {
            Object[] ids = (Object[]) value.getArray();
            List<UUID> replicas = new ArrayList<>(ids.length);
            for (Object id : ids) replicas.add((UUID) id);
            return List.copyOf(replicas);
        } finally { value.free(); }
    }
    private static IOException databaseError(SQLException error) { return new IOException("Metadata database operation failed", error); }
    private static MessageDigest digest(String algorithm) {
        try { return MessageDigest.getInstance(algorithm); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private InputStream verifiedObject(List<Segment> segments, Metadata metadata) throws IOException {
        Path staged = Files.createTempFile("objectstore-read-", ".pending");
        boolean ready = false;
        try {
            MessageDigest hash = digest("SHA-256");
            long count;
            try (InputStream source = new DigestInputStream(new SegmentStream(segments), hash);
                 OutputStream output = Files.newOutputStream(staged)) {
                count = source.transferTo(output);
            }
            if (count != metadata.length() || !MessageDigest.isEqual(hash.digest(), metadata.sha256()))
                throw new IOException("Object manifest failed integrity verification");
            InputStream file = Files.newInputStream(staged);
            ready = true;
            return new FilterInputStream(file) {
                @Override public void close() throws IOException {
                    try { super.close(); }
                    finally { Files.deleteIfExists(staged); }
                }
            };
        } finally { if (!ready) Files.deleteIfExists(staged); }
    }
    @Override public boolean ready() {
        if (!nodes.availableHostsAtLeast(2, testNodeDomains)) return false;
        try (Connection connection = connect(); var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT 1")) {
            return result.next() && result.getInt(1) == 1;
        } catch (SQLException error) { return false; }
    }
    RepairReport repairOnce() throws IOException {
        int scanned = 0, restored = 0, underReplicated = 0, unrecoverable = 0;
        try (Connection reader = connect()) {
            reader.setAutoCommit(false);
            try (PreparedStatement query = reader.prepareStatement(
                "SELECT s.generation, s.ordinal, s.segment_id, s.length, s.sha256, s.replica_ids, s.placement_version FROM cluster_segments s JOIN cluster_objects o ON o.generation=s.generation ORDER BY s.generation, s.ordinal")) {
                query.setFetchSize(128);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        scanned++;
                        RepairTarget target = new RepairTarget((UUID) result.getObject(1), result.getInt(2),
                            result.getLong(7), new Segment((UUID) result.getObject(3), result.getInt(4),
                                result.getBytes(5), replicaIds(result, 6)));
                        Segment segment = target.segment();
                        byte[] copy = null;
                        Set<UUID> healthy = new HashSet<>();
                        Set<UUID> healthyHosts = new HashSet<>();
                        for (UUID id : segment.replicas()) {
                            int node = nodes.index(id);
                            if (node < 0) continue;
                            byte[] candidate = readableReplica(node, segment);
                            if (candidate == null) continue;
                            if (copy == null) copy = candidate;
                            healthy.add(id);
                            healthyHosts.add(nodes.faultDomain(node, testNodeDomains));
                        }
                        if (copy == null) { unrecoverable++; continue; }
                        for (int node : PlacementPolicy.candidates(segment.id(), nodes, testNodeDomains)) {
                            UUID host = nodes.faultDomain(node, testNodeDomains);
                            if (healthyHosts.contains(host)) continue;
                            if (repairReplica(node, segment, copy)) {
                                healthy.add(nodes.node(node).id());
                                healthyHosts.add(host);
                                restored++;
                            }
                            if (healthyHosts.size() == 3) break;
                        }
                        if (healthyHosts.size() < 3) underReplicated++;
                        Set<UUID> listed = new java.util.LinkedHashSet<>(segment.replicas());
                        listed.addAll(healthy);
                        if (listed.size() != segment.replicas().size()) {
                            try (Connection writer = connect(); PreparedStatement update = writer.prepareStatement(
                                "UPDATE cluster_segments SET replica_ids=?, placement_version=placement_version+1 WHERE generation=? AND ordinal=? AND placement_version=?")) {
                                update.setArray(1, writer.createArrayOf("uuid", listed.toArray()));
                                update.setObject(2, target.generation());
                                update.setInt(3, target.ordinal());
                                update.setLong(4, target.version());
                                update.executeUpdate();
                            }
                        }
                    }
                }
            }
            reader.commit();
        } catch (SQLException error) { throw databaseError(error); }
        return new RepairReport(scanned, restored, underReplicated, unrecoverable);
    }

    private byte[] readableReplica(int node, Segment segment) {
        try { return nodes.get(node, segment.id(), segment.length(), segment.hash()); }
        catch (IOException unavailable) { return null; }
    }

    private boolean repairReplica(int node, Segment segment, byte[] copy) {
        try {
            nodes.repair(node, segment.id(), copy, segment.hash());
            return true;
        } catch (IOException unavailable) { return false; }
    }

    @Override public void close() {}

    private final class SegmentStream extends InputStream {
        private final List<Segment> segments;
        private int position;
        private ByteArrayInputStream current;
        private boolean closed;
        SegmentStream(List<Segment> segments) { this.segments = segments; }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (closed) throw new IOException("Object stream is closed");
            if (length == 0) return 0;
            while (current == null || current.available() == 0) {
                if (position == segments.size()) return -1;
                Segment segment = segments.get(position++);
                IOException failure = null;
                for (UUID replica : segment.replicas()) {
                    int node = nodes.index(replica);
                    if (node < 0) continue;
                    try {
                        byte[] bytes = nodes.get(node, segment.id(), segment.length(), segment.hash());
                        current = new ByteArrayInputStream(bytes);
                        break;
                    } catch (IOException error) { failure = error; }
                }
                if (current == null || current.available() == 0)
                    throw new StoreException(503, "SlowDown", "No verified replica is currently available", failure);
            }
            return current.read(buffer, offset, length);
        }
        @Override public void close() { closed = true; current = null; }
    }
}
