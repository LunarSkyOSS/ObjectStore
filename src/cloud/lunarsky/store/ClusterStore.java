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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ClusterStore implements ObjectStorage, MultipartStorage {
    private record Segment(UUID id, int length, byte[] hash, List<UUID> replicas) {}
    private record RepairTarget(UUID id, int part, int ordinal, long version, Segment segment) {}
    private record Upload(String contentType, Map<String, String> userMetadata,
                          Map<String, String> tags, Map<String, String> acl) {}
    private record StoredPart(long length, String etag, List<Segment> segments) {}
    record RepairReport(int scanned, int restored, int rebalanced, int underReplicated, int unrecoverable) {}
    record GcReport(int scanned, int eligible, int deleted, int unavailableNodes) {}
    private final String jdbcUrl, user, password, configuredBucket;
    private final NodeClient nodes;
    private final long maxObject, maxTotal;
    private final boolean testNodeDomains;

    ClusterStore(String jdbcUrl, String user, String password, String bucket,
                 List<URI> nodeUrls, String token, String repairToken, long maxObject, long maxTotal,
                 boolean testNodeDomains) throws IOException {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://") || user == null || password == null)
            throw new IllegalArgumentException("Invalid metadata database configuration");
        this.jdbcUrl = jdbcUrl;
        this.user = user;
        this.password = password;
        this.configuredBucket = bucket;
        this.maxObject = maxObject;
        this.maxTotal = maxTotal;
        this.testNodeDomains = testNodeDomains;
        try (Connection connection = connect()) {
            int format = SchemaMigrator.prepare(connection, bucket);
            if (format != 2) throw new IOException("Legacy replica positions require objectstore cluster-migrate before this gateway can start");
            nodes = NodeRegistry.load(connection, nodeUrls, token, repairToken);
        } catch (SQLException error) { throw databaseError(error); }
    }

    private Connection connect() throws SQLException { return DriverManager.getConnection(jdbcUrl, user, password); }

    @Override public Limits limits() { return new Limits(maxObject, maxTotal); }

    @Override public void ensureBucket(String bucket) throws IOException {
        try { bucket(bucket); }
        catch (StoreException error) {
            if (error.status != 404) throw error;
            try { createBucket(bucket); }
            catch (StoreException created) {
                if (created.status != 409) throw created;
            }
        }
    }

    @Override public Bucket bucket(String name) throws IOException {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
            "SELECT created_at, versioning_state, acl FROM cluster_buckets WHERE name=?")) {
            query.setString(1, name);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new StoreException(404, "NoSuchBucket", "Bucket not found");
                return new Bucket(name, result.getLong(1), VersioningState.valueOf(result.getString(2)),
                    ObjectAttributes.decode(result.getBytes(3)));
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    @Override public List<Bucket> buckets() throws IOException {
        List<Bucket> result = new ArrayList<>();
        try (Connection connection = connect(); var query = connection.createStatement();
             ResultSet rows = query.executeQuery("SELECT name, created_at, versioning_state, acl FROM cluster_buckets ORDER BY name")) {
            while (rows.next()) result.add(new Bucket(rows.getString(1), rows.getLong(2),
                VersioningState.valueOf(rows.getString(3)), ObjectAttributes.decode(rows.getBytes(4))));
            return result;
        } catch (SQLException error) { throw databaseError(error); }
    }

    @Override public void setVersioning(String bucket, VersioningState state) throws IOException {
        if (state == VersioningState.NEVER)
            throw new StoreException(400, "InvalidArgument", "Versioning cannot be disabled after it is enabled");
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                lockUsage(connection, bucket);
                VersioningState old = versioningState(connection, bucket);
                if (old == VersioningState.NEVER && state == VersioningState.SUSPENDED)
                    throw new StoreException(400, "InvalidArgument", "Enable versioning before suspending it");
                try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE cluster_buckets SET versioning_state=? WHERE name=?")) {
                    update.setString(1, state.name());
                    update.setString(2, bucket);
                    update.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw databaseError(sql);
                throw error;
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    @Override public void setBucketAcl(String bucket, Map<String, String> acl) throws IOException {
        try (Connection connection = connect(); PreparedStatement update = connection.prepareStatement(
            "UPDATE cluster_buckets SET acl=? WHERE name=?")) {
            update.setBytes(1, ObjectAttributes.encode(acl, 2048));
            update.setString(2, bucket);
            if (update.executeUpdate() == 0)
                throw new StoreException(404, "NoSuchBucket", "Bucket not found");
        } catch (SQLException error) { throw databaseError(error); }
    }

    private static VersioningState versioningState(Connection connection, String bucket) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT versioning_state FROM cluster_buckets WHERE name=? FOR UPDATE")) {
            query.setString(1, bucket);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new StoreException(404, "NoSuchBucket", "Bucket not found");
                return VersioningState.valueOf(result.getString(1));
            }
        }
    }

    private static VersioningState readVersioningState(Connection connection, String bucket) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT versioning_state FROM cluster_buckets WHERE name=?")) {
            query.setString(1, bucket);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new StoreException(404, "NoSuchBucket", "Bucket not found");
                return VersioningState.valueOf(result.getString(1));
            }
        }
    }

    @Override public void createBucket(String name) throws IOException {
        if (!name.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))
            throw new StoreException(400, "InvalidBucketName", "Invalid bucket name");
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.execute("SELECT pg_advisory_xact_lock(6834071092784)");
                }
                try (var statement = connection.createStatement();
                     ResultSet count = statement.executeQuery("SELECT count(*) FROM cluster_buckets")) {
                    count.next();
                    if (count.getLong(1) >= 1000)
                        throw new StoreException(400, "TooManyBuckets", "Bucket limit reached");
                }
                try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cluster_buckets (name, created_at) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
                    insert.setString(1, name);
                    insert.setLong(2, Instant.now().toEpochMilli());
                    if (insert.executeUpdate() == 0)
                        throw new StoreException(409, "BucketAlreadyOwnedByYou", "Bucket already exists");
                }
                try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cluster_usage VALUES (?, 0)")) {
                    insert.setString(1, name);
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw databaseError(sql);
                throw error;
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    @Override public void deleteBucket(String name) throws IOException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                lockUsage(connection, name);
                try (PreparedStatement check = connection.prepareStatement(
                    "SELECT EXISTS (SELECT 1 FROM cluster_object_versions WHERE bucket=?) OR EXISTS " +
                    "(SELECT 1 FROM cluster_uploads WHERE bucket=?)")) {
                    check.setString(1, name);
                    check.setString(2, name);
                    try (ResultSet result = check.executeQuery()) {
                        result.next();
                        if (result.getBoolean(1))
                            throw new StoreException(409, "BucketNotEmpty", "Bucket contains objects or uploads");
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM cluster_buckets WHERE name=?")) {
                    delete.setString(1, name);
                    if (delete.executeUpdate() == 0)
                        throw new StoreException(404, "NoSuchBucket", "Bucket not found");
                }
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM cluster_usage WHERE bucket=?")) {
                    delete.setString(1, name);
                    delete.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw databaseError(sql);
                throw error;
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    private static void lockGc(Connection connection, boolean shared) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_lock" + (shared ? "_shared" : "") + "(6834071092783)");
        }
    }

    @Override public Metadata put(String bucket, String key, InputStream input, long length, String expectedHash,
                                  String checksum, boolean createOnly, String contentType,
                                  Map<String, String> userMetadata, Map<String, String> tags,
                                  java.util.function.Supplier<Map<String, String>> checksums,
                                  Map<String, String> acl) throws IOException {
        validatePut(bucket, length, contentType);
        MessageDigest md5 = digest("MD5");
        Crc64Nvme crc64 = new Crc64Nvme();
        Path staged = Files.createTempFile("objectstore-cluster-", ".pending");
        try {
            byte[] fullHash = stageInput(staged, input, length, expectedHash, checksum, md5, crc64);
            Map<String, String> suppliedChecksums = checksums.get();
            Map<String, String> storedChecksums = suppliedChecksums.isEmpty() ?
                Map.of("x-amz-checksum-crc64nvme", crc64.encoded()) : Map.copyOf(suppliedChecksums);
            checkCapacity(bucket, key, length, createOnly);
            try (Connection connection = connect()) {
                lockGc(connection, true);
                List<Segment> segments = uploadSegments(staged, length);
                Metadata metadata = new Metadata(length, Instant.now().toEpochMilli(),
                    HexFormat.of().formatHex(md5.digest()), fullHash, bucket, key, contentType,
                    Map.copyOf(userMetadata), Map.copyOf(tags), null, storedChecksums, Map.copyOf(acl));
                return persistObject(connection, metadata, segments, createOnly);
            } catch (SQLException error) { throw databaseError(error); }
        } finally { Files.deleteIfExists(staged); }
    }

    private void validatePut(String bucket, long length, String contentType) throws IOException {
        bucket(bucket);
        if (length < 0) throw new StoreException(411, "MissingContentLength", "Content-Length is required");
        if (length > maxObject) throw new StoreException(413, "EntityTooLarge", "Object exceeds the configured size limit");
        if (!nodes.availableHostsAtLeast(2, testNodeDomains))
            throw new StoreException(503, "SlowDown", "Fewer than two storage hosts are available");
        if (contentType.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255)
            throw new StoreException(400, "InvalidArgument", "Content-Type is too long");
    }

    private byte[] stageInput(Path staged, InputStream input, long length, String expectedHash,
                              String checksum, MessageDigest md5, Crc64Nvme crc64) throws IOException {
        MessageDigest sha = digest("SHA-256");
        try (OutputStream output = Files.newOutputStream(staged)) {
            byte[] buffer = new byte[65536];
            long remaining = length;
            while (remaining > 0) {
                int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) throw new StoreException(400, "IncompleteBody", "Payload length does not match Content-Length");
                if (count == 0) continue;
                sha.update(buffer, 0, count);
                md5.update(buffer, 0, count);
                crc64.update(buffer, 0, count);
                output.write(buffer, 0, count);
                remaining -= count;
            }
        }
        if (input.read() != -1) throw new StoreException(413, "EntityTooLarge", "Payload exceeds declared size");
        byte[] fullHash = sha.digest();
        if (expectedHash != null && !HexFormat.of().formatHex(fullHash).equals(expectedHash))
            throw new StoreException(400, "XAmzContentSHA256Mismatch", "Payload hash mismatch");
        if (checksum != null && !Base64.getEncoder().encodeToString(fullHash).equals(checksum))
            throw new StoreException(400, "BadDigest", "SHA-256 checksum mismatch");
        return fullHash;
    }

    private void checkCapacity(String bucket, String key, long length, boolean createOnly) throws IOException {
        try (Connection connection = connect()) {
            bucket(bucket);
            long previous = currentLength(connection, bucket, key);
            if (createOnly && previous >= 0)
                throw new StoreException(412, "PreconditionFailed", "Object already exists");
            long replaced = readVersioningState(connection, bucket) == VersioningState.ENABLED ? 0 :
                nullVersionLength(connection, bucket, key);
            if (maxTotal - (occupiedBytes(connection) - replaced) < length)
                throw new StoreException(507, "InsufficientStorage", "Store capacity limit reached");
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

    private Metadata persistObject(Connection connection, Metadata metadata, List<Segment> segments,
                                   boolean createOnly) throws IOException {
        String bucket = metadata.bucket(), key = metadata.key();
        long length = metadata.length();
        UUID generation = UUID.randomUUID();
        try {
            connection.setAutoCommit(false);
            try {
                long used = lockUsage(connection, bucket);
                VersioningState state = versioningState(connection, bucket);
                long previous = currentLength(connection, bucket, key);
                if (createOnly && previous >= 0)
                    throw new StoreException(412, "PreconditionFailed", "Object already exists");
                long replaced = state == VersioningState.ENABLED ? 0 : nullVersionLength(connection, bucket, key);
                if (maxTotal - (occupiedBytes(connection) - replaced) < length)
                    throw new StoreException(507, "InsufficientStorage", "Store capacity limit reached");
                try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cluster_segments (generation, ordinal, segment_id, length, sha256, replicas, replica_ids) VALUES (?, ?, ?, ?, ?, 'v2', ?)")) {
                    for (int i = 0; i < segments.size(); i++) {
                        Segment segment = segments.get(i);
                        insert.setObject(1, generation);
                        insert.setInt(2, i);
                        insert.setObject(3, segment.id());
                        insert.setInt(4, segment.length());
                        insert.setBytes(5, segment.hash());
                        insert.setArray(6, connection.createArrayOf("uuid", segment.replicas().toArray()));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                Metadata stored = publishObject(connection, metadata, generation, used, replaced, state);
                connection.commit();
                return stored;
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw databaseError(sql);
                throw error;
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    private static long nullVersionLength(Connection connection, String bucket, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT length FROM cluster_object_versions WHERE bucket=? AND object_key=? AND version_id='null'")) {
            query.setString(1, bucket);
            query.setString(2, key);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private Metadata publishObject(Connection connection, Metadata metadata, UUID generation,
                                   long used, long replaced, VersioningState state) throws SQLException {
        String bucket = metadata.bucket(), key = metadata.key();
        String id = state == VersioningState.ENABLED ? UUID.randomUUID().toString() : "null";
        if (id.equals("null")) {
            try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM cluster_object_versions WHERE bucket=? AND object_key=? AND version_id='null'")) {
                delete.setString(1, bucket);
                delete.setString(2, key);
                delete.executeUpdate();
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO cluster_object_versions (bucket, object_key, version_id, delete_marker, generation, " +
            "length, modified, etag, sha256, content_type, user_metadata, tags, checksum_metadata, acl) " +
            "VALUES (?, ?, ?, false, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, bucket);
            insert.setString(2, key);
            insert.setString(3, id);
            insert.setObject(4, generation);
            insert.setLong(5, metadata.length());
            insert.setLong(6, metadata.modified());
            insert.setString(7, metadata.etag());
            insert.setBytes(8, metadata.sha256());
            insert.setString(9, metadata.contentType());
            insert.setBytes(10, ObjectAttributes.encode(metadata.userMetadata(), 4096));
            insert.setBytes(11, ObjectAttributes.encode(metadata.tags(), 8192));
            insert.setBytes(12, ObjectAttributes.encode(metadata.checksums(), 512));
            insert.setBytes(13, ObjectAttributes.encode(metadata.acl(), 2048));
            insert.executeUpdate();
        }
        setHead(connection, bucket, key, id);
        writeCurrentObject(connection, metadata, generation);
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE cluster_usage SET used_bytes=? WHERE bucket=?")) {
            update.setLong(1, used - replaced + metadata.length());
            update.setString(2, bucket);
            update.executeUpdate();
        }
        try (PreparedStatement delete = connection.prepareStatement(
            "DELETE FROM cluster_tombstones WHERE bucket=? AND object_key=?")) {
            delete.setString(1, bucket);
            delete.setString(2, key);
            delete.executeUpdate();
        }
        return new Metadata(metadata.length(), metadata.modified(), metadata.etag(), metadata.sha256(),
            bucket, key, metadata.contentType(), metadata.userMetadata(), metadata.tags(),
            state == VersioningState.NEVER ? null : id, metadata.checksums(), metadata.acl());
    }

    private static void setHead(Connection connection, String bucket, String key, String id) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
            "INSERT INTO cluster_object_heads VALUES (?, ?, ?) ON CONFLICT (bucket, object_key) " +
            "DO UPDATE SET version_id=EXCLUDED.version_id")) {
            update.setString(1, bucket);
            update.setString(2, key);
            update.setString(3, id);
            update.executeUpdate();
        }
    }

    private static void writeCurrentObject(Connection connection, Metadata metadata,
                                           UUID generation) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
            "INSERT INTO cluster_objects (bucket, object_key, generation, length, modified, etag, sha256, " +
            "content_type, user_metadata, tags) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (bucket, object_key) DO UPDATE SET generation=EXCLUDED.generation, " +
            "length=EXCLUDED.length, modified=EXCLUDED.modified, etag=EXCLUDED.etag, " +
            "sha256=EXCLUDED.sha256, content_type=EXCLUDED.content_type, " +
            "user_metadata=EXCLUDED.user_metadata, tags=EXCLUDED.tags")) {
            bindObject(update, metadata, generation);
            update.executeUpdate();
        }
    }

    @Override public String create(String bucket, String key, String contentType,
                                   Map<String, String> userMetadata, Map<String, String> tags,
                                   Map<String, String> acl) throws IOException {
        bucket(bucket);
        if (contentType == null || contentType.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255)
            throw new StoreException(400, "InvalidArgument", "Invalid Content-Type");
        UUID id = UUID.randomUUID();
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                lockUsage(connection, bucket);
                try (PreparedStatement count = connection.prepareStatement("SELECT count(*) FROM cluster_uploads WHERE bucket=?")) {
                    count.setString(1, bucket);
                    try (ResultSet result = count.executeQuery()) {
                        result.next();
                        if (result.getLong(1) >= 32)
                            throw new StoreException(503, "SlowDown", "Too many active uploads");
                    }
                }
                try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cluster_uploads (upload_id, bucket, object_key, content_type, created_at, user_metadata, tags, acl) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    insert.setObject(1, id);
                    insert.setString(2, bucket);
                    insert.setString(3, key);
                    insert.setString(4, contentType);
                    insert.setLong(5, Instant.now().toEpochMilli());
                    insert.setBytes(6, ObjectAttributes.encode(userMetadata, 4096));
                    insert.setBytes(7, ObjectAttributes.encode(tags, 8192));
                    insert.setBytes(8, ObjectAttributes.encode(acl, 2048));
                    insert.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw databaseError(sql);
                throw error;
            }
        } catch (SQLException error) { throw databaseError(error); }
        return id.toString();
    }

    @Override public String putPart(String id, String bucket, String key, int number, InputStream input,
                                    long length, String expectedHash, String checksum) throws IOException {
        if (number < 1 || number > 10000) throw new StoreException(400, "InvalidArgument", "Invalid part number");
        validatePut(bucket, length, "application/octet-stream");
        UUID uploadId = uploadId(id);
        try (Connection connection = connect()) { upload(connection, uploadId, bucket, key, false); }
        catch (SQLException error) { throw databaseError(error); }
        Path staged = Files.createTempFile("objectstore-part-", ".pending");
        MessageDigest md5 = digest("MD5");
        try {
            stageInput(staged, input, length, expectedHash, checksum, md5, new Crc64Nvme());
            try (Connection connection = connect()) {
                lockGc(connection, true);
                List<Segment> segments = uploadSegments(staged, length);
                String etag = HexFormat.of().formatHex(md5.digest());
                connection.setAutoCommit(false);
                try {
                    long used = lockUsage(connection, bucket);
                    upload(connection, uploadId, bucket, key, true);
                    long previous = partLength(connection, uploadId, number);
                    if (maxTotal - (occupiedBytes(connection) - previous) < length)
                        throw new StoreException(507, "InsufficientStorage", "Multipart staging limit reached");
                    try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO cluster_upload_parts VALUES (?, ?, ?, ?, ?) ON CONFLICT (upload_id, part_number) DO UPDATE SET length=EXCLUDED.length, etag=EXCLUDED.etag, modified=EXCLUDED.modified")) {
                        insert.setObject(1, uploadId);
                        insert.setInt(2, number);
                        insert.setLong(3, length);
                        insert.setString(4, etag);
                        insert.setLong(5, Instant.now().toEpochMilli());
                        insert.executeUpdate();
                    }
                    try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM cluster_upload_segments WHERE upload_id=? AND part_number=?")) {
                        delete.setObject(1, uploadId);
                        delete.setInt(2, number);
                        delete.executeUpdate();
                    }
                    try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO cluster_upload_segments VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                        for (int ordinal = 0; ordinal < segments.size(); ordinal++) {
                            Segment segment = segments.get(ordinal);
                            insert.setObject(1, uploadId);
                            insert.setInt(2, number);
                            insert.setInt(3, ordinal);
                            insert.setObject(4, segment.id());
                            insert.setInt(5, segment.length());
                            insert.setBytes(6, segment.hash());
                            insert.setArray(7, connection.createArrayOf("uuid", segment.replicas().toArray()));
                            insert.addBatch();
                        }
                        insert.executeBatch();
                    }
                    connection.commit();
                } catch (SQLException | RuntimeException error) {
                    connection.rollback();
                    if (error instanceof SQLException sql) throw databaseError(sql);
                    throw error;
                }
                return etag;
            } catch (SQLException error) { throw databaseError(error); }
        } finally { Files.deleteIfExists(staged); }
    }

    @Override public Metadata complete(String id, String bucket, String key, List<MultipartStorage.Part> parts) throws IOException {
        if (parts.isEmpty() || parts.size() > 10000)
            throw new StoreException(400, "InvalidPart", "No valid parts supplied");
        UUID uploadId = uploadId(id);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                long used = lockUsage(connection, bucket);
                VersioningState state = versioningState(connection, bucket);
                Upload upload = upload(connection, uploadId, bucket, key, true);
                long uploadBytes = uploadLength(connection, uploadId);
                MessageDigest fullHash = digest("SHA-256");
                MessageDigest etagHash = digest("MD5");
                Crc64Nvme crc64 = new Crc64Nvme();
                List<Segment> selected = new ArrayList<>();
                long total = 0;
                int last = 0;
                for (MultipartStorage.Part requested : parts) {
                    if (requested.number() <= last || requested.number() > 10000)
                        throw new StoreException(400, "InvalidPartOrder", "Parts must be in ascending order");
                    last = requested.number();
                    StoredPart part = storedPart(connection, uploadId, requested.number());
                    if (part == null || !part.etag().equals(requested.etag().replace("\"", "")))
                        throw new StoreException(400, "InvalidPart", "Part ETag mismatch");
                    if (part.length() > maxObject - total)
                        throw new StoreException(413, "EntityTooLarge", "Object exceeds the configured size limit");
                    total += part.length();
                    MessageDigest partHash = digest("MD5");
                    for (Segment segment : part.segments()) {
                        byte[] bytes = readableSegment(segment);
                        if (bytes == null) throw new StoreException(503, "SlowDown", "A part has no verified replica");
                        fullHash.update(bytes);
                        crc64.update(bytes, 0, bytes.length);
                        partHash.update(bytes);
                        selected.add(segment);
                    }
                    byte[] md5 = partHash.digest();
                    if (!part.etag().equals(HexFormat.of().formatHex(md5)))
                        throw new StoreException(503, "SlowDown", "A part failed integrity verification");
                    etagHash.update(md5);
                }
                long replaced = state == VersioningState.ENABLED ? 0 : nullVersionLength(connection, bucket, key);
                if (maxTotal - (occupiedBytes(connection) - replaced - uploadBytes) < total)
                    throw new StoreException(507, "InsufficientStorage", "Store capacity limit reached");
                Metadata metadata = new Metadata(total, Instant.now().toEpochMilli(),
                    HexFormat.of().formatHex(etagHash.digest()) + "-" + parts.size(), fullHash.digest(),
                    bucket, key, upload.contentType(), upload.userMetadata(), upload.tags(), null,
                    Map.of("x-amz-checksum-crc64nvme", crc64.encoded()), upload.acl());
                UUID generation = UUID.randomUUID();
                try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cluster_segments (generation, ordinal, segment_id, length, sha256, replicas, replica_ids) VALUES (?, ?, ?, ?, ?, 'v2', ?)")) {
                    for (int ordinal = 0; ordinal < selected.size(); ordinal++) {
                        Segment segment = selected.get(ordinal);
                        insert.setObject(1, generation);
                        insert.setInt(2, ordinal);
                        insert.setObject(3, segment.id());
                        insert.setInt(4, segment.length());
                        insert.setBytes(5, segment.hash());
                        insert.setArray(6, connection.createArrayOf("uuid", segment.replicas().toArray()));
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                Metadata stored = publishObject(connection, metadata, generation, used, replaced, state);
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM cluster_uploads WHERE upload_id=?")) {
                    delete.setObject(1, uploadId);
                    delete.executeUpdate();
                }
                connection.commit();
                return stored;
            } catch (SQLException | IOException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw databaseError(sql);
                if (error instanceof IOException io) throw io;
                throw error;
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    @Override public void abort(String id, String bucket, String key) throws IOException {
        UUID uploadId = uploadId(id);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                lockUsage(connection, bucket);
                upload(connection, uploadId, bucket, key, true);
                try (PreparedStatement delete = connection.prepareStatement("DELETE FROM cluster_uploads WHERE upload_id=?")) {
                    delete.setObject(1, uploadId);
                    delete.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw databaseError(sql);
                throw error;
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    @Override public PartPage listParts(String id, String bucket, String key, int marker, int maxParts)
            throws IOException {
        UUID uploadId = uploadId(id);
        try (Connection connection = connect()) {
            upload(connection, uploadId, bucket, key, false);
            List<PartInfo> parts = new ArrayList<>();
            boolean truncated = false;
            try (PreparedStatement query = connection.prepareStatement(
                "SELECT part_number, length, etag, modified FROM cluster_upload_parts WHERE upload_id=? AND part_number>? ORDER BY part_number LIMIT ?")) {
                query.setObject(1, uploadId);
                query.setInt(2, marker);
                query.setInt(3, maxParts + 1);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        if (parts.size() == maxParts) {
                            truncated = true;
                            break;
                        }
                        parts.add(new PartInfo(result.getInt(1), result.getLong(2), result.getString(3), result.getLong(4)));
                    }
                }
            }
            int next = parts.isEmpty() ? marker : parts.getLast().number();
            return new PartPage(parts, next, truncated);
        } catch (SQLException error) { throw databaseError(error); }
    }

    @Override public List<UploadInfo> listUploads(String bucket, String prefix) throws IOException {
        bucket(bucket);
        List<UploadInfo> uploads = new ArrayList<>();
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
            "SELECT upload_id, object_key, created_at FROM cluster_uploads WHERE bucket=?")) {
            query.setString(1, bucket);
            try (ResultSet result = query.executeQuery()) {
                while (result.next()) {
                    String key = result.getString(2);
                    if (key.startsWith(prefix))
                        uploads.add(new UploadInfo(result.getObject(1).toString(), key, result.getLong(3)));
                }
            }
        } catch (SQLException error) { throw databaseError(error); }
        uploads.sort(Comparator.comparing(UploadInfo::key).thenComparing(UploadInfo::id));
        return uploads;
    }

    @Override public int activeUploads() {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
            "SELECT count(*) FROM cluster_uploads WHERE bucket=?")) {
            query.setString(1, configuredBucket);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (SQLException error) { throw new IllegalStateException("Could not count multipart uploads", error); }
    }

    @Override public long stagedBytes() {
        try (Connection connection = connect()) { return stagedBytes(connection, configuredBucket); }
        catch (SQLException error) { throw new IllegalStateException("Could not count staged bytes", error); }
    }

    @Override public OpenObject open(String bucket, String key) throws IOException {
        return open(bucket, key, null);
    }

    @Override public OpenObject open(String bucket, String key, String versionId) throws IOException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            lockGc(connection, true);
            try {
                Metadata metadata;
                UUID generation;
                try (PreparedStatement query = connection.prepareStatement(
                    versionId == null ?
                    "SELECT v.version_id, v.delete_marker, v.generation, v.length, v.modified, v.etag, " +
                    "v.sha256, v.content_type, v.user_metadata, v.tags, v.checksum_metadata, v.acl FROM cluster_object_heads h " +
                    "JOIN cluster_object_versions v ON v.bucket=h.bucket AND v.object_key=h.object_key " +
                    "AND v.version_id=h.version_id WHERE h.bucket=? AND h.object_key=?" :
                    "SELECT version_id, delete_marker, generation, length, modified, etag, sha256, " +
                    "content_type, user_metadata, tags, checksum_metadata, acl FROM cluster_object_versions WHERE bucket=? " +
                    "AND object_key=? AND version_id=?")) {
                    query.setString(1, bucket);
                    query.setString(2, key);
                    if (versionId != null) query.setString(3, versionId);
                    try (ResultSet result = query.executeQuery()) {
                        if (!result.next()) throw new StoreException(404,
                            versionId == null ? "NoSuchKey" : "NoSuchVersion", "Object version not found");
                        if (result.getBoolean(2))
                            throw StoreException.deletedVersion(result.getString(1), result.getLong(5),
                                versionId != null);
                        generation = (UUID) result.getObject(3);
                        String storedId = result.getString(1);
                        boolean unversioned = versionId == null && storedId.equals("null") &&
                            readVersioningState(connection, bucket) == VersioningState.NEVER;
                        metadata = new Metadata(result.getLong(4), result.getLong(5), result.getString(6),
                            result.getBytes(7), bucket, key, result.getString(8),
                            ObjectAttributes.decode(result.getBytes(9)), ObjectAttributes.decode(result.getBytes(10)),
                            unversioned ? null : storedId, ObjectAttributes.decode(result.getBytes(11)),
                            ObjectAttributes.decode(result.getBytes(12)));
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
        delete(bucket, key, null);
    }

    @Override public DeleteResult delete(String bucket, String key, String versionId) throws IOException {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                long used = lockUsage(connection, bucket);
                VersioningState state = versioningState(connection, bucket);
                long removedLength = 0;
                boolean removedMarker = false;
                String resultId = null;
                if (versionId != null) {
                    try (PreparedStatement query = connection.prepareStatement(
                        "SELECT delete_marker, length FROM cluster_object_versions WHERE bucket=? " +
                        "AND object_key=? AND version_id=?")) {
                        query.setString(1, bucket);
                        query.setString(2, key);
                        query.setString(3, versionId);
                        try (ResultSet result = query.executeQuery()) {
                            if (!result.next())
                                throw new StoreException(404, "NoSuchVersion", "Object version not found");
                            removedMarker = result.getBoolean(1);
                            removedLength = removedMarker ? 0 : result.getLong(2);
                        }
                    }
                    removeVersionRow(connection, bucket, key, versionId);
                    refreshCurrent(connection, bucket, key);
                    resultId = versionId;
                } else if (state == VersioningState.NEVER) {
                    removedLength = nullVersionLength(connection, bucket, key);
                    removeVersionRow(connection, bucket, key, "null");
                    refreshCurrent(connection, bucket, key);
                } else {
                    resultId = state == VersioningState.ENABLED ? UUID.randomUUID().toString() : "null";
                    if (state == VersioningState.SUSPENDED) {
                        removedLength = nullVersionLength(connection, bucket, key);
                        removeVersionRow(connection, bucket, key, "null");
                    }
                    try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO cluster_object_versions (bucket, object_key, version_id, " +
                        "delete_marker, modified) VALUES (?, ?, ?, true, ?)")) {
                        insert.setString(1, bucket);
                        insert.setString(2, key);
                        insert.setString(3, resultId);
                        insert.setLong(4, Instant.now().toEpochMilli());
                        insert.executeUpdate();
                    }
                    setHead(connection, bucket, key, resultId);
                    removeCurrentObject(connection, bucket, key);
                    recordTombstone(connection, bucket, key);
                }
                try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE cluster_usage SET used_bytes=? WHERE bucket=?")) {
                    update.setLong(1, used - removedLength);
                    update.setString(2, bucket);
                    update.executeUpdate();
                }
                connection.commit();
                return new DeleteResult(resultId, versionId == null && state != VersioningState.NEVER || removedMarker);
            } catch (SQLException | IOException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw databaseError(sql);
                if (error instanceof IOException io) throw io;
                throw error;
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    private static void removeVersionRow(Connection connection, String bucket, String key,
                                         String versionId) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
            "DELETE FROM cluster_object_versions WHERE bucket=? AND object_key=? AND version_id=?")) {
            delete.setString(1, bucket);
            delete.setString(2, key);
            delete.setString(3, versionId);
            delete.executeUpdate();
        }
    }

    private static void removeCurrentObject(Connection connection, String bucket, String key) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
            "DELETE FROM cluster_objects WHERE bucket=? AND object_key=?")) {
            delete.setString(1, bucket);
            delete.setString(2, key);
            delete.executeUpdate();
        }
    }

    private static void recordTombstone(Connection connection, String bucket, String key) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
            "INSERT INTO cluster_tombstones VALUES (?, ?, ?, ?) ON CONFLICT (bucket, object_key) " +
            "DO UPDATE SET generation=EXCLUDED.generation, deleted_at=EXCLUDED.deleted_at")) {
            update.setString(1, bucket);
            update.setString(2, key);
            update.setObject(3, UUID.randomUUID());
            update.setLong(4, Instant.now().toEpochMilli());
            update.executeUpdate();
        }
    }

    private static void refreshCurrent(Connection connection, String bucket, String key)
        throws SQLException, IOException {
        try (PreparedStatement latest = connection.prepareStatement(
            "SELECT version_id, delete_marker, generation, length, modified, etag, sha256, " +
            "content_type, user_metadata, tags FROM cluster_object_versions WHERE bucket=? " +
            "AND object_key=? ORDER BY sequence DESC LIMIT 1")) {
            latest.setString(1, bucket);
            latest.setString(2, key);
            try (ResultSet result = latest.executeQuery()) {
                if (!result.next()) {
                    try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM cluster_object_heads WHERE bucket=? AND object_key=?")) {
                        delete.setString(1, bucket);
                        delete.setString(2, key);
                        delete.executeUpdate();
                    }
                    removeCurrentObject(connection, bucket, key);
                    recordTombstone(connection, bucket, key);
                    return;
                }
                setHead(connection, bucket, key, result.getString(1));
                if (result.getBoolean(2)) {
                    removeCurrentObject(connection, bucket, key);
                    recordTombstone(connection, bucket, key);
                } else {
                    Metadata metadata = new Metadata(result.getLong(4), result.getLong(5),
                        result.getString(6), result.getBytes(7), bucket, key, result.getString(8),
                        ObjectAttributes.decode(result.getBytes(9)), ObjectAttributes.decode(result.getBytes(10)));
                    writeCurrentObject(connection, metadata, (UUID) result.getObject(3));
                    try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM cluster_tombstones WHERE bucket=? AND object_key=?")) {
                        delete.setString(1, bucket);
                        delete.setString(2, key);
                        delete.executeUpdate();
                    }
                }
            }
        }
    }

    @Override public Map<String, String> tags(String bucket, String key) throws IOException {
        return tags(bucket, key, null);
    }

    @Override public Map<String, String> tags(String bucket, String key, String versionId) throws IOException {
        try (Connection connection = connect(); PreparedStatement query = connection.prepareStatement(
            versionId == null ? "SELECT v.tags, v.delete_marker FROM cluster_object_heads h " +
                "JOIN cluster_object_versions v ON v.bucket=h.bucket AND v.object_key=h.object_key " +
                "AND v.version_id=h.version_id WHERE h.bucket=? AND h.object_key=?" :
                "SELECT tags, delete_marker FROM cluster_object_versions WHERE bucket=? AND object_key=? " +
                "AND version_id=?")) {
            query.setString(1, bucket);
            query.setString(2, key);
            if (versionId != null) query.setString(3, versionId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || result.getBoolean(2))
                    throw new StoreException(404, versionId == null ? "NoSuchKey" : "NoSuchVersion",
                        "Object version not found");
                return ObjectAttributes.decode(result.getBytes(1));
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    @Override public void setTags(String bucket, String key, Map<String, String> tags) throws IOException {
        setTags(bucket, key, null, tags);
    }

    @Override public void setTags(String bucket, String key, String versionId,
                                  Map<String, String> tags) throws IOException {
        byte[] encoded = ObjectAttributes.encode(tags, 8192);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                lockUsage(connection, bucket);
                String selected = versionId;
                if (selected == null) {
                    try (PreparedStatement head = connection.prepareStatement(
                        "SELECT version_id FROM cluster_object_heads WHERE bucket=? AND object_key=?")) {
                        head.setString(1, bucket);
                        head.setString(2, key);
                        try (ResultSet result = head.executeQuery()) {
                            if (!result.next()) throw new StoreException(404, "NoSuchKey", "Object not found");
                            selected = result.getString(1);
                        }
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE cluster_object_versions SET tags=? WHERE bucket=? AND object_key=? " +
                    "AND version_id=? AND NOT delete_marker")) {
                    update.setBytes(1, encoded);
                    update.setString(2, bucket);
                    update.setString(3, key);
                    update.setString(4, selected);
                    if (update.executeUpdate() == 0)
                        throw new StoreException(404, versionId == null ? "NoSuchKey" : "NoSuchVersion",
                            "Object version not found");
                }
                try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE cluster_objects SET tags=? WHERE bucket=? AND object_key=? AND EXISTS " +
                    "(SELECT 1 FROM cluster_object_heads WHERE bucket=? AND object_key=? AND version_id=?)")) {
                    update.setBytes(1, encoded);
                    update.setString(2, bucket);
                    update.setString(3, key);
                    update.setString(4, bucket);
                    update.setString(5, key);
                    update.setString(6, selected);
                    update.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                if (error instanceof SQLException sql) throw databaseError(sql);
                throw error;
            }
        } catch (SQLException error) { throw databaseError(error); }
    }

    @Override public void setObjectAcl(String bucket, String key, String versionId,
                                       Map<String, String> acl) throws IOException {
        byte[] encoded = ObjectAttributes.encode(acl, 2048);
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                lockUsage(connection, bucket);
                String selected = versionId;
                if (selected == null) {
                    try (PreparedStatement head = connection.prepareStatement(
                        "SELECT version_id FROM cluster_object_heads WHERE bucket=? AND object_key=?")) {
                        head.setString(1, bucket);
                        head.setString(2, key);
                        try (ResultSet result = head.executeQuery()) {
                            if (!result.next()) throw new StoreException(404, "NoSuchKey", "Object not found");
                            selected = result.getString(1);
                        }
                    }
                }
                try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE cluster_object_versions SET acl=? WHERE bucket=? AND object_key=? " +
                    "AND version_id=? AND NOT delete_marker")) {
                    update.setBytes(1, encoded);
                    update.setString(2, bucket);
                    update.setString(3, key);
                    update.setString(4, selected);
                    if (update.executeUpdate() == 0)
                        throw new StoreException(404, versionId == null ? "NoSuchKey" : "NoSuchVersion",
                            "Object version not found");
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

    @Override public VersionPage listVersions(String bucket, String prefix, String keyMarker,
                                              String versionMarker, int maxKeys) throws IOException {
        if (versionMarker != null && keyMarker == null)
            throw new StoreException(400, "InvalidArgument", "Version marker requires a key marker");
        bucket(bucket);
        if (maxKeys == 0) return new VersionPage(List.of(), null, null, false);
        List<VersionEntry> page = new ArrayList<>();
        String nextKey = null, nextVersion = null;
        boolean truncated = false;
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try (PreparedStatement query = connection.prepareStatement(
                "SELECT v.object_key, v.version_id, v.delete_marker, v.length, v.modified, v.etag, " +
                "v.sha256, v.content_type, v.user_metadata, v.tags, h.version_id=v.version_id, " +
                "v.checksum_metadata " +
                "FROM cluster_object_versions v LEFT JOIN cluster_object_heads h ON " +
                "h.bucket=v.bucket AND h.object_key=v.object_key WHERE v.bucket=? AND v.object_key>=? " +
                "ORDER BY v.object_key, v.sequence DESC")) {
                query.setString(1, bucket);
                query.setString(2, keyMarker != null && keyMarker.compareTo(prefix) > 0 ? keyMarker : prefix);
                query.setFetchSize(128);
                try (ResultSet rows = query.executeQuery()) {
                    boolean pastMarker = versionMarker == null;
                    while (rows.next()) {
                        String key = rows.getString(1), id = rows.getString(2);
                        if (!key.startsWith(prefix)) break;
                        if (keyMarker != null && key.compareTo(keyMarker) < 0) continue;
                        if (keyMarker != null && key.compareTo(keyMarker) > 0) pastMarker = true;
                        if (keyMarker != null && key.equals(keyMarker)) {
                            if (versionMarker == null) continue;
                            if (!pastMarker) {
                                if (id.equals(versionMarker)) pastMarker = true;
                                continue;
                            }
                        }
                        if (page.size() == maxKeys) {
                            truncated = true;
                            break;
                        }
                        boolean marker = rows.getBoolean(3);
                        Metadata metadata = marker ? null : new Metadata(rows.getLong(4), rows.getLong(5),
                            rows.getString(6), rows.getBytes(7), bucket, key, rows.getString(8),
                            ObjectAttributes.decode(rows.getBytes(9)), ObjectAttributes.decode(rows.getBytes(10)),
                            id, ObjectAttributes.decode(rows.getBytes(12)));
                        page.add(new VersionEntry(key, id, rows.getLong(5), marker, rows.getBoolean(11), metadata));
                        nextKey = key;
                        nextVersion = id;
                    }
                }
            }
            connection.commit();
        } catch (SQLException error) { throw databaseError(error); }
        return new VersionPage(page, truncated ? nextKey : null, truncated ? nextVersion : null, truncated);
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
            if (group != null && group.equals(activePrefix)) {
                lastKey = key;
                continue;
            }
            if (entries.size() + prefixes.size() >= maxKeys) {
                truncated = true;
                break;
            }
            if (group != null) {
                prefixes.add(group);
                activePrefix = group;
            } else {
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
        try (var statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_xact_lock(6834071092784)");
        }
        try (PreparedStatement query = connection.prepareStatement("SELECT used_bytes FROM cluster_usage WHERE bucket=? FOR UPDATE")) {
            query.setString(1, bucket);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new StoreException(404, "NoSuchBucket", "Bucket not found");
                return result.getLong(1);
            }
        }
    }

    private static long occupiedBytes(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet result = statement.executeQuery(
            "SELECT (SELECT COALESCE(sum(used_bytes), 0) FROM cluster_usage) + " +
            "(SELECT COALESCE(sum(length), 0) FROM cluster_upload_parts)")) {
            result.next();
            return result.getLong(1);
        }
    }

    private static UUID uploadId(String id) {
        try {
            if (id == null || !id.matches("[0-9a-f-]{36}")) throw new IllegalArgumentException();
            return UUID.fromString(id);
        } catch (IllegalArgumentException error) {
            throw new StoreException(404, "NoSuchUpload", "Upload not found");
        }
    }

    private static Upload upload(Connection connection, UUID id, String bucket, String key, boolean lock)
            throws SQLException, IOException {
        String sql = "SELECT bucket, object_key, content_type, created_at, user_metadata, tags, acl FROM cluster_uploads WHERE upload_id=?" +
            (lock ? " FOR UPDATE" : "");
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setObject(1, id);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next() || !result.getString(1).equals(bucket) || !result.getString(2).equals(key))
                    throw new StoreException(404, "NoSuchUpload", "Upload not found");
                return new Upload(result.getString(3), ObjectAttributes.decode(result.getBytes(5)),
                    ObjectAttributes.decode(result.getBytes(6)), ObjectAttributes.decode(result.getBytes(7)));
            }
        }
    }

    private static long stagedBytes(Connection connection, String bucket) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT COALESCE(sum(p.length), 0) FROM cluster_upload_parts p JOIN cluster_uploads u USING (upload_id) WHERE u.bucket=?")) {
            query.setString(1, bucket);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static long uploadLength(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT COALESCE(sum(length), 0) FROM cluster_upload_parts WHERE upload_id=?")) {
            query.setObject(1, id);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static long partLength(Connection connection, UUID id, int number) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT length FROM cluster_upload_parts WHERE upload_id=? AND part_number=?")) {
            query.setObject(1, id);
            query.setInt(2, number);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private static StoredPart storedPart(Connection connection, UUID id, int number) throws SQLException, IOException {
        long length;
        String etag;
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT length, etag FROM cluster_upload_parts WHERE upload_id=? AND part_number=?")) {
            query.setObject(1, id);
            query.setInt(2, number);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                length = result.getLong(1);
                etag = result.getString(2);
            }
        }
        List<Segment> segments = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(
            "SELECT ordinal, segment_id, length, sha256, replica_ids FROM cluster_upload_segments WHERE upload_id=? AND part_number=? ORDER BY ordinal")) {
            query.setObject(1, id);
            query.setInt(2, number);
            try (ResultSet result = query.executeQuery()) {
                long total = 0;
                while (result.next()) {
                    if (result.getInt(1) != segments.size()) throw new IOException("Incomplete multipart manifest");
                    Segment segment = new Segment((UUID) result.getObject(2), result.getInt(3),
                        result.getBytes(4), replicaIds(result, 5));
                    total = Math.addExact(total, segment.length());
                    segments.add(segment);
                }
                if (total != length) throw new IOException("Incomplete multipart manifest");
            }
        }
        return new StoredPart(length, etag, segments);
    }

    private byte[] readableSegment(Segment segment) {
        for (UUID id : segment.replicas()) {
            int index = nodes.index(id);
            if (index < 0) continue;
            byte[] bytes = readableReplica(index, segment);
            if (bytes != null) return bytes;
        }
        return null;
    }

    private long currentLength(Connection connection, String bucket, String key) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT length FROM cluster_objects WHERE bucket=? AND object_key=?")) {
            query.setString(1, bucket);
            query.setString(2, key);
            try (ResultSet result = query.executeQuery()) { return result.next() ? result.getLong(1) : -1; }
        }
    }
    private static void bindObject(PreparedStatement update, Metadata data, UUID generation) throws SQLException {
        update.setString(1, data.bucket());
        update.setString(2, data.key());
        update.setObject(3, generation);
        update.setLong(4, data.length());
        update.setLong(5, data.modified());
        update.setString(6, data.etag());
        update.setBytes(7, data.sha256());
        update.setString(8, data.contentType());
        update.setBytes(9, ObjectAttributes.encode(data.userMetadata(), 4096));
        update.setBytes(10, ObjectAttributes.encode(data.tags(), 8192));
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
        int scanned = 0, restored = 0, rebalanced = 0, underReplicated = 0, unrecoverable = 0;
        try (Connection reader = connect()) {
            reader.setAutoCommit(false);
            try (var lock = reader.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(6834071092782)");
            }
            try (PreparedStatement query = reader.prepareStatement(
                "SELECT s.generation, 0, s.ordinal, s.segment_id, s.length, s.sha256, s.replica_ids, s.placement_version " +
                "FROM cluster_segments s JOIN cluster_object_versions o ON o.generation=s.generation " +
                "UNION ALL SELECT s.upload_id, s.part_number, s.ordinal, s.segment_id, s.length, s.sha256, " +
                "s.replica_ids, s.placement_version FROM cluster_upload_segments s " +
                "ORDER BY 1, 2, 3")) {
                query.setFetchSize(128);
                try (ResultSet result = query.executeQuery()) {
                    while (result.next()) {
                        scanned++;
                        RepairTarget target = new RepairTarget((UUID) result.getObject(1), result.getInt(2),
                            result.getInt(3), result.getLong(8), new Segment((UUID) result.getObject(4),
                                result.getInt(5), result.getBytes(6), replicaIds(result, 7)));
                        Segment segment = target.segment();
                        byte[] copy = null;
                        Set<UUID> healthy = new LinkedHashSet<>();
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
                        if (copy == null) {
                            unrecoverable++;
                            continue;
                        }
                        List<UUID> preferred = new ArrayList<>();
                        Set<UUID> preferredHosts = new HashSet<>();
                        for (int node : PlacementPolicy.candidates(segment.id(), nodes, testNodeDomains)) {
                            UUID host = nodes.faultDomain(node, testNodeDomains);
                            if (!preferredHosts.add(host)) continue;
                            preferred.add(nodes.node(node).id());
                            if (preferred.size() == 3) break;
                        }
                        for (UUID id : preferred) {
                            int node = nodes.index(id);
                            UUID host = nodes.faultDomain(node, testNodeDomains);
                            if (healthy.contains(id)) continue;
                            if (repairReplica(node, segment, copy)) {
                                healthy.add(id);
                                healthyHosts.add(host);
                                restored++;
                            }
                        }
                        if (healthyHosts.size() < 3) underReplicated++;
                        List<UUID> listed = new ArrayList<>();
                        Set<UUID> listedHosts = new HashSet<>();
                        for (UUID id : preferred) {
                            if (healthy.contains(id)) {
                                listed.add(id);
                                listedHosts.add(nodes.faultDomain(nodes.index(id), testNodeDomains));
                            }
                        }
                        for (UUID id : segment.replicas()) {
                            if (listed.size() == 3) break;
                            int node = nodes.index(id);
                            if (healthy.contains(id) && node >= 0 &&
                                listedHosts.add(nodes.faultDomain(node, testNodeDomains))) listed.add(id);
                        }
                        if (listed.size() < 3) {
                            for (UUID id : segment.replicas()) {
                                if (!listed.contains(id)) listed.add(id);
                            }
                        }
                        if (!listed.equals(segment.replicas())) {
                            String table = target.part() == 0 ? "cluster_segments" : "cluster_upload_segments";
                            String identity = target.part() == 0 ? "generation=? AND ordinal=?" :
                                "upload_id=? AND part_number=? AND ordinal=?";
                            try (Connection writer = connect(); PreparedStatement update = writer.prepareStatement(
                                "UPDATE " + table + " SET replica_ids=?, placement_version=placement_version+1 WHERE " +
                                    identity + " AND placement_version=?")) {
                                update.setArray(1, writer.createArrayOf("uuid", listed.toArray()));
                                update.setObject(2, target.id());
                                int next = 3;
                                if (target.part() != 0) update.setInt(next++, target.part());
                                update.setInt(next++, target.ordinal());
                                update.setLong(next, target.version());
                                if (update.executeUpdate() == 1 && preferred.stream().anyMatch(id ->
                                    !segment.replicas().contains(id) && listed.contains(id))) rebalanced++;
                            }
                        }
                    }
                }
            }
            reader.commit();
        } catch (SQLException error) { throw databaseError(error); }
        return new RepairReport(scanned, restored, rebalanced, underReplicated, unrecoverable);
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

    GcReport collectGarbage(long minimumAgeMillis, boolean apply) throws IOException {
        if (minimumAgeMillis < 0 || (minimumAgeMillis == 0 && !testNodeDomains))
            throw new IllegalArgumentException("Invalid garbage collection age");
        if (nodes.count() < 2 || nodes.repairTokenUnavailable())
            throw new IllegalStateException("Garbage collection requires repair authority");
        int scanned = 0, eligible = 0, deleted = 0, unavailable = 0;
        try (Connection connection = connect()) {
            try (var lock = connection.createStatement()) {
                lock.execute("SELECT pg_advisory_lock(6834071092782)");
            }
            lockGc(connection, false);
            try (PreparedStatement referenced = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM cluster_segments s JOIN cluster_object_versions o " +
                "ON o.generation=s.generation WHERE s.segment_id=? AND ?=ANY(s.replica_ids) " +
                "UNION ALL SELECT 1 FROM cluster_upload_segments s " +
                "WHERE s.segment_id=? AND ?=ANY(s.replica_ids))");
                 PreparedStatement candidate = connection.prepareStatement(
                     "SELECT observed_mtime, first_seen FROM cluster_gc_candidates WHERE node_id=? AND segment_id=?");
                 PreparedStatement mark = connection.prepareStatement(
                     "INSERT INTO cluster_gc_candidates VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING");
                 PreparedStatement reset = connection.prepareStatement(
                     "UPDATE cluster_gc_candidates SET observed_mtime=?, first_seen=? WHERE node_id=? AND segment_id=?");
                 PreparedStatement clear = connection.prepareStatement(
                     "DELETE FROM cluster_gc_candidates WHERE node_id=? AND segment_id=?")) {
                for (int node = 0; node < nodes.count(); node++) {
                    boolean reachable = true;
                    for (int shard = 0; shard < 256 && reachable; shard++) {
                        String prefix = "%02x".formatted(shard);
                        UUID after = null;
                        while (true) {
                            List<NodeClient.StoredSegment> page;
                            try { page = nodes.inventory(node, prefix, after); }
                            catch (IOException error) {
                                System.err.println("Cluster inventory failed for node " + nodes.node(node).id() +
                                    ": " + error.getMessage());
                                unavailable++;
                                reachable = false;
                                break;
                            }
                            for (NodeClient.StoredSegment segment : page) {
                                scanned++;
                                UUID nodeId = nodes.node(node).id();
                                referenced.setObject(1, segment.id());
                                referenced.setObject(2, nodeId);
                                referenced.setObject(3, segment.id());
                                referenced.setObject(4, nodeId);
                                try (ResultSet result = referenced.executeQuery()) {
                                    result.next();
                                    if (result.getBoolean(1)) {
                                        if (apply) clearCandidate(clear, nodeId, segment.id());
                                        continue;
                                    }
                                }
                                eligible++;
                                if (apply) {
                                    candidate.setObject(1, nodeId);
                                    candidate.setObject(2, segment.id());
                                    long now = System.currentTimeMillis();
                                    boolean firstObservation = false;
                                    long firstSeen = now;
                                    try (ResultSet result = candidate.executeQuery()) {
                                        if (!result.next()) firstObservation = true;
                                        else if (result.getLong(1) != segment.modified()) firstObservation = true;
                                        else firstSeen = result.getLong(2);
                                    }
                                    if (firstObservation) {
                                        reset.setLong(1, segment.modified());
                                        reset.setLong(2, now);
                                        reset.setObject(3, nodeId);
                                        reset.setObject(4, segment.id());
                                        if (reset.executeUpdate() == 0) {
                                            mark.setObject(1, nodeId);
                                            mark.setObject(2, segment.id());
                                            mark.setLong(3, segment.modified());
                                            mark.setLong(4, now);
                                            mark.executeUpdate();
                                        }
                                        continue;
                                    }
                                    if (now - firstSeen < minimumAgeMillis) continue;
                                    try {
                                        if (nodes.deleteOrphan(node, segment, minimumAgeMillis)) deleted++;
                                        clearCandidate(clear, nodeId, segment.id());
                                    } catch (IOException error) {
                                        System.err.println("Cluster deletion failed for segment " + segment.id() +
                                            ": " + error.getMessage());
                                        unavailable++;
                                        reachable = false;
                                        break;
                                    }
                                }
                            }
                            if (!reachable || page.size() < 1000) break;
                            after = page.getLast().id();
                        }
                    }
                }
            }
        } catch (SQLException error) { throw databaseError(error); }
        return new GcReport(scanned, eligible, deleted, unavailable);
    }

    private static void clearCandidate(PreparedStatement clear, UUID node, UUID segment) throws SQLException {
        clear.setObject(1, node);
        clear.setObject(2, segment);
        clear.executeUpdate();
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
        @Override public void close() {
            closed = true;
            current = null;
        }
    }
}
