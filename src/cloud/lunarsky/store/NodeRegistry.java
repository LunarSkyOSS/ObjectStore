package cloud.lunarsky.store;

import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class NodeRegistry {
    private NodeRegistry() {}

    static NodeClient.Node join(Connection connection, URI url, UUID expectedHost, String token) throws IOException {
        NodeIdentity identity = NodeClient.probe(url, token);
        if (!identity.hostId().equals(expectedHost))
            throw new IOException("The node reported a different physical host ID");
        try {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(6834071092781)");
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT node_id, host_id, endpoint FROM cluster_nodes WHERE node_id=? OR endpoint=?")) {
                query.setObject(1, identity.nodeId());
                query.setString(2, url.toString());
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) {
                        if (!identity.nodeId().equals(result.getObject(1)) ||
                            !identity.hostId().equals(result.getObject(2)) || !url.toString().equals(result.getString(3)))
                            throw new IOException("Node ID, host ID, or endpoint conflicts with an existing registration");
                        if (result.next()) throw new IOException("Conflicting node registrations");
                        connection.commit();
                        return new NodeClient.Node(identity.nodeId(), identity.hostId(), url);
                    }
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cluster_nodes (node_id, host_id, endpoint, state) VALUES (?, ?, ?, 'active')")) {
                insert.setObject(1, identity.nodeId());
                insert.setObject(2, identity.hostId());
                insert.setString(3, url.toString());
                insert.executeUpdate();
            }
            connection.commit();
            return new NodeClient.Node(identity.nodeId(), identity.hostId(), url);
        } catch (SQLException | IOException error) {
            try { connection.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
            if (error instanceof IOException io) throw io;
            throw new IOException("Node registration failed", error);
        } finally {
            try { connection.setAutoCommit(true); }
            catch (SQLException error) { throw new IOException("Could not restore metadata connection", error); }
        }
    }

    static NodeClient load(Connection connection, List<URI> urls, String token, String repairToken) throws IOException {
        if (urls.size() < 2 || urls.stream().distinct().count() != urls.size())
            throw new IllegalArgumentException("At least two distinct node URLs are required");
        try {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(6834071092781)");
            }
            Map<String, NodeClient.Node> stored = registeredNodes(connection);
            if (stored.isEmpty()) registerInitialNodes(connection, urls, token, stored);
            List<NodeClient.Node> configured = configuredNodes(urls, token, stored);
            ensureLiveReplicasConfigured(connection, configured);
            NodeClient nodes = new NodeClient(configured, token, repairToken);
            connection.commit();
            return nodes;
        } catch (SQLException | IOException | RuntimeException error) {
            try { connection.rollback(); } catch (SQLException rollback) { error.addSuppressed(rollback); }
            if (error instanceof IOException io) throw io;
            if (error instanceof SQLException sql) throw new IOException("Node registry check failed", sql);
            throw (RuntimeException) error;
        } finally {
            try { connection.setAutoCommit(true); }
            catch (SQLException error) { throw new IOException("Could not restore metadata connection", error); }
        }
    }

    private static Map<String, NodeClient.Node> registeredNodes(Connection connection) throws SQLException {
        Map<String, NodeClient.Node> stored = new HashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT node_id, host_id, endpoint FROM cluster_nodes WHERE state <> 'retired'")) {
            while (result.next()) {
                URI url = URI.create(result.getString(3));
                stored.put(url.toString(), new NodeClient.Node((UUID) result.getObject(1),
                    (UUID) result.getObject(2), url));
            }
        }
        return stored;
    }

    private static void registerInitialNodes(Connection connection, List<URI> urls, String token,
                                             Map<String, NodeClient.Node> stored) throws SQLException, IOException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM cluster_segments)")) {
            result.next();
            if (result.getBoolean(1)) throw new IOException("Existing segments have no registered node identities");
        }
        for (URI url : urls) {
            NodeIdentity identity = NodeClient.probe(url, token);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cluster_nodes (node_id, host_id, endpoint, state) VALUES (?, ?, ?, 'active')")) {
                insert.setObject(1, identity.nodeId());
                insert.setObject(2, identity.hostId());
                insert.setString(3, url.toString());
                insert.executeUpdate();
            }
            stored.put(url.toString(), new NodeClient.Node(identity.nodeId(), identity.hostId(), url));
        }
    }

    private static List<NodeClient.Node> configuredNodes(List<URI> urls, String token,
                                                         Map<String, NodeClient.Node> stored) throws IOException {
        List<NodeClient.Node> configured = new ArrayList<>();
        for (URI url : urls) {
            NodeClient.Node node = stored.get(url.toString());
            if (node == null) throw new IOException("Unregistered storage node URL: " + url);
            NodeIdentity actual = null;
            try { actual = NodeClient.probe(url, token); }
            catch (IOException offline) { }
            if (actual != null && (!actual.nodeId().equals(node.id()) || !actual.hostId().equals(node.hostId())))
                throw new IOException("Storage node identity changed at " + url);
            configured.add(node);
        }
        return configured;
    }

    private static void ensureLiveReplicasConfigured(Connection connection, List<NodeClient.Node> configured)
        throws SQLException, IOException {
        Set<UUID> configuredIds = new HashSet<>();
        for (NodeClient.Node node : configured) configuredIds.add(node.id());
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                 "SELECT DISTINCT unnest(s.replica_ids) FROM cluster_segments s JOIN cluster_objects o ON o.generation=s.generation")) {
            while (result.next()) {
                UUID id = (UUID) result.getObject(1);
                if (!configuredIds.contains(id))
                    throw new IOException("A live segment refers to a node missing from CLUSTER_NODES: " + id);
            }
        }
    }
}
