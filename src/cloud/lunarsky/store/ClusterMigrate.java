package cloud.lunarsky.store;

import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ClusterMigrate {
    private ClusterMigrate() {}

    public static void main(String[] args) throws Exception {
        if ((args.length != 1 || !args[0].equals("--check")) &&
            (args.length != 2 || !args[0].equals("--apply")))
            throw new IllegalArgumentException("Usage: objectstore cluster-migrate --check | --apply node-id-0,node-id-1,node-id-2");
        Map<String, String> env = System.getenv();
        if (!"cluster".equals(env.get("STORE_MODE")) || !"true".equals(env.get("CLUSTER_LOCAL_DEV")))
            throw new IllegalArgumentException("Migration is enabled only in local cluster mode");
        List<URI> urls = Arrays.stream(env.get("CLUSTER_NODES").split(",", -1)).map(URI::create).toList();
        if (urls.size() != 3) throw new IllegalArgumentException("Legacy migration requires the original three URLs in their original order");
        String token = env.get("CLUSTER_TOKEN");
        List<NodeClient.Node> addresses = new ArrayList<>();
        for (URI url : urls) {
            NodeIdentity identity = NodeClient.probe(url, token);
            addresses.add(new NodeClient.Node(identity.nodeId(), identity.hostId(), url));
        }
        NodeClient nodes = new NodeClient(addresses, token, null);
        if (args[0].equals("--apply")) {
            String actual = addresses.stream().map(node -> node.id().toString())
                .collect(java.util.stream.Collectors.joining(","));
            if (!actual.equals(args[1]))
                throw new IllegalArgumentException("Confirmed legacy node mapping differs from the current ordered node identities");
        }
        try (Connection connection = DriverManager.getConnection(env.get("POSTGRES_JDBC_URL"),
                env.get("POSTGRES_USER"), env.get("POSTGRES_PASSWORD"))) {
            int format = SchemaMigrator.prepare(connection, env.get("S3_BUCKET"));
            System.out.println("cluster_format=" + format);
            if (format == 2) return;
            for (int index = 0; index < nodes.count(); index++) {
                NodeClient.Node node = nodes.node(index);
                System.out.println("legacy_" + index + "=" + node.id() + " host=" + node.hostId() +
                    " endpoint=" + node.url());
            }
            long verified = verifyLiveSegments(connection, nodes);
            System.out.println("live_segments_verified=" + verified);
            if (args[0].equals("--check")) return;
            apply(connection, nodes);
            System.out.println("cluster_format=2");
        }
    }

    private static long verifyLiveSegments(Connection connection, NodeClient nodes) throws SQLException, IOException {
        long verified = 0;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT s.segment_id, s.length, s.sha256, s.replicas FROM cluster_segments s JOIN cluster_objects o ON o.generation=s.generation");
             ResultSet result = query.executeQuery()) {
            while (result.next()) {
                UUID segment = (UUID) result.getObject(1);
                int length = result.getInt(2);
                byte[] hash = result.getBytes(3);
                for (int index : legacyIndices(result.getString(4), nodes.count())) {
                    try {
                        nodes.get(index, segment, length, hash);
                    } catch (IOException offlineOrCorrupt) {
                        throw new IOException("A listed live replica is unavailable or corrupt: segment " +
                            segment + " legacy node " + index, offlineOrCorrupt);
                    }
                }
                verified++;
            }
        }
        return verified;
    }

    private static List<Integer> legacyIndices(String text, int count) throws IOException {
        if (text == null || text.isBlank()) throw new IOException("Missing legacy replica list");
        List<Integer> indices = new ArrayList<>();
        Set<Integer> unique = new HashSet<>();
        for (String part : text.split(",", -1)) {
            int index;
            try { index = Integer.parseInt(part); }
            catch (NumberFormatException error) { throw new IOException("Invalid legacy replica index", error); }
            if (index < 0 || index >= count || !unique.add(index))
                throw new IOException("Invalid or duplicate legacy replica index");
            indices.add(index);
        }
        return indices;
    }

    private static void apply(Connection connection, NodeClient nodes) throws SQLException, IOException {
        try {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(6834071092781)");
                try (ResultSet result = statement.executeQuery("SELECT version FROM cluster_format WHERE singleton=1 FOR UPDATE")) {
                    if (!result.next() || result.getInt(1) != 1)
                        throw new IOException("Cluster format changed during migration");
                }
                try (ResultSet result = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM cluster_nodes)")) {
                    result.next();
                    if (result.getBoolean(1)) throw new IOException("Legacy migration already has node bindings");
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cluster_nodes (node_id, host_id, endpoint, legacy_index, state) VALUES (?, ?, ?, ?, 'active')")) {
                for (int index = 0; index < nodes.count(); index++) {
                    NodeClient.Node node = nodes.node(index);
                    insert.setObject(1, node.id());
                    insert.setObject(2, node.hostId());
                    insert.setString(3, node.url().toString());
                    insert.setInt(4, index);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            try (PreparedStatement select = connection.prepareStatement(
                     "SELECT generation, ordinal, replicas FROM cluster_segments WHERE replica_ids IS NULL");
                 ResultSet result = select.executeQuery();
                 PreparedStatement update = connection.prepareStatement(
                     "UPDATE cluster_segments SET replica_ids=? WHERE generation=? AND ordinal=? AND replica_ids IS NULL")) {
                while (result.next()) {
                    List<UUID> ids = new ArrayList<>();
                    for (int index : legacyIndices(result.getString(3), nodes.count()))
                        ids.add(nodes.node(index).id());
                    update.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
                    update.setObject(2, result.getObject(1));
                    update.setInt(3, result.getInt(2));
                    if (update.executeUpdate() != 1) throw new IOException("Segment changed during migration");
                }
            }
            try (Statement statement = connection.createStatement()) {
                try (ResultSet result = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM cluster_segments WHERE replica_ids IS NULL)")) {
                    result.next();
                    if (result.getBoolean(1)) throw new IOException("Unconverted legacy segments remain");
                }
                statement.executeUpdate("UPDATE cluster_format SET version=2 WHERE singleton=1 AND version=1");
                statement.execute("ALTER TABLE cluster_segments VALIDATE CONSTRAINT cluster_replica_ids_required");
            }
            connection.commit();
        } catch (SQLException | IOException | RuntimeException error) {
            try { connection.rollback(); }
            catch (SQLException rollback) { error.addSuppressed(rollback); }
            if (error instanceof IOException io) throw io;
            if (error instanceof SQLException sql) throw sql;
            throw (RuntimeException) error;
        } finally { connection.setAutoCommit(true); }
    }
}
