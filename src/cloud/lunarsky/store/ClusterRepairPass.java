package cloud.lunarsky.store;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static cloud.lunarsky.store.ClusterStore.RepairReport;

final class ClusterRepairPass {
    private record Target(UUID owner, int part, int ordinal, long version,
                          UUID segment, int length, byte[] hash, List<UUID> replicas) {}
    private final ClusterMonitor.Connections connections;
    private final NodeClient nodes;
    private final boolean testNodeDomains;
    private final ClusterMonitor monitor;
    private final Map<UUID, Long> verifiedCounts = new HashMap<>();
    private int scanned;
    private int restored;
    private int rebalanced;
    private int underReplicated;
    private int unrecoverable;

    ClusterRepairPass(ClusterMonitor.Connections connections, NodeClient nodes,
                  boolean testNodeDomains, ClusterMonitor monitor) {
        this.connections = connections;
        this.nodes = nodes;
        this.testNodeDomains = testNodeDomains;
        this.monitor = monitor;
    }

    RepairReport run() throws IOException {
        try (Connection reader = connections.open()) {
            reader.setAutoCommit(false);
            try (var lock = reader.createStatement()) {
                lock.execute("SELECT pg_advisory_xact_lock(6834071092782)");
            }
            long startedAt = System.currentTimeMillis();
            monitor.recordRepairStatus("running", startedAt, report());
            try {
                scan(reader, startedAt);
                monitor.completeRepair(startedAt, report(), verifiedCounts);
                reader.commit();
                return report();
            } catch (SQLException | IOException | RuntimeException error) {
                monitor.recordRepairStatus("failed", startedAt, report());
                throw error;
            }
        } catch (SQLException error) {
            throw new IOException("Metadata database operation failed", error);
        }
    }

    private RepairReport report() {
        return new RepairReport(scanned, restored, rebalanced, underReplicated, unrecoverable);
    }

    private void scan(Connection reader, long startedAt) throws SQLException, IOException {
        long lastProgressAt = startedAt;
        try (PreparedStatement query = reader.prepareStatement(
                "SELECT s.generation, 0, s.ordinal, s.segment_id, s.length, s.sha256, s.replica_ids, s.placement_version " +
                "FROM cluster_segments s JOIN cluster_object_versions o ON o.generation=s.generation " +
                "UNION ALL SELECT s.upload_id, s.part_number, s.ordinal, s.segment_id, s.length, s.sha256, " +
                "s.replica_ids, s.placement_version FROM cluster_upload_segments s ORDER BY 1, 2, 3")) {
            query.setFetchSize(128);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    scanned++;
                    repair(target(rows));
                    long now = System.currentTimeMillis();
                    if (scanned % 256 == 0 || now - lastProgressAt >= 5000) {
                        monitor.recordRepairStatus("running", startedAt, report());
                        lastProgressAt = now;
                    }
                }
            }
        }
    }

    private static Target target(ResultSet rows) throws SQLException, IOException {
        java.sql.Array value = rows.getArray(7);
        if (value == null) throw new IOException("Segment has no migrated replica identities");
        try {
            List<UUID> replicas = new ArrayList<>();
            for (Object id : (Object[]) value.getArray()) replicas.add((UUID) id);
            return new Target((UUID) rows.getObject(1), rows.getInt(2), rows.getInt(3),
                rows.getLong(8), (UUID) rows.getObject(4), rows.getInt(5), rows.getBytes(6),
                List.copyOf(replicas));
        } finally {
            value.free();
        }
    }

    private void repair(Target target) throws SQLException {
        Set<UUID> healthy = new LinkedHashSet<>();
        Set<UUID> healthyHosts = new HashSet<>();
        byte[] copy = readReplicas(target, healthy, healthyHosts);
        if (copy == null) {
            unrecoverable++;
            return;
        }
        List<UUID> preferred = preferred(target.segment());
        for (UUID id : preferred) {
            int node = nodes.index(id);
            if (healthy.contains(id)) continue;
            if (repairReplica(node, target, copy)) {
                healthy.add(id);
                healthyHosts.add(nodes.faultDomain(node, testNodeDomains));
                restored++;
            }
        }
        if (healthyHosts.size() < 3) underReplicated++;
        List<UUID> listed = placement(target, preferred, healthy);
        boolean published = listed.equals(target.replicas()) || updatePlacement(target, listed);
        if (published && preferred.stream().anyMatch(id ->
                !target.replicas().contains(id) && listed.contains(id))) rebalanced++;
        for (UUID id : published ? listed : target.replicas()) {
            if (healthy.contains(id)) verifiedCounts.merge(id, 1L, Long::sum);
        }
    }

    private byte[] readReplicas(Target target, Set<UUID> healthy, Set<UUID> hosts) {
        byte[] copy = null;
        for (UUID id : target.replicas()) {
            int node = nodes.index(id);
            if (node < 0) continue;
            byte[] candidate = readableReplica(node, target);
            if (candidate == null) continue;
            if (copy == null) copy = candidate;
            healthy.add(id);
            hosts.add(nodes.faultDomain(node, testNodeDomains));
        }
        return copy;
    }

    private byte[] readableReplica(int node, Target target) {
        try { return nodes.get(node, target.segment(), target.length(), target.hash()); }
        catch (IOException unavailable) { return null; }
    }

    private boolean repairReplica(int node, Target target, byte[] copy) {
        try {
            nodes.repair(node, target.segment(), copy, target.hash());
            return true;
        } catch (IOException unavailable) { return false; }
    }

    private List<UUID> preferred(UUID segment) {
        List<UUID> preferred = new ArrayList<>();
        Set<UUID> hosts = new HashSet<>();
        for (int node : PlacementPolicy.candidates(segment, nodes, testNodeDomains)) {
            if (!hosts.add(nodes.faultDomain(node, testNodeDomains))) continue;
            preferred.add(nodes.node(node).id());
            if (preferred.size() == 3) break;
        }
        return preferred;
    }

    private List<UUID> placement(Target target, List<UUID> preferred, Set<UUID> healthy) {
        List<UUID> listed = new ArrayList<>();
        Set<UUID> hosts = new HashSet<>();
        for (UUID id : preferred) {
            if (healthy.contains(id)) {
                listed.add(id);
                hosts.add(nodes.faultDomain(nodes.index(id), testNodeDomains));
            }
        }
        for (UUID id : target.replicas()) {
            if (listed.size() == 3) break;
            int node = nodes.index(id);
            if (healthy.contains(id) && node >= 0 &&
                hosts.add(nodes.faultDomain(node, testNodeDomains))) listed.add(id);
        }
        if (listed.size() < 3) {
            for (UUID id : target.replicas()) {
                if (!listed.contains(id)) listed.add(id);
            }
        }
        return listed;
    }

    private boolean updatePlacement(Target target, List<UUID> listed) throws SQLException {
        String table = target.part() == 0 ? "cluster_segments" : "cluster_upload_segments";
        String identity = target.part() == 0 ? "generation=? AND ordinal=?" :
            "upload_id=? AND part_number=? AND ordinal=?";
        try (Connection writer = connections.open(); PreparedStatement update = writer.prepareStatement(
                "UPDATE " + table + " SET replica_ids=?, placement_version=placement_version+1 WHERE " +
                identity + " AND placement_version=?")) {
            java.sql.Array ids = writer.createArrayOf("uuid", listed.toArray());
            try {
                update.setArray(1, ids);
                update.setObject(2, target.owner());
                int next = 3;
                if (target.part() != 0) update.setInt(next++, target.part());
                update.setInt(next++, target.ordinal());
                update.setLong(next, target.version());
                return update.executeUpdate() == 1;
            } finally {
                ids.free();
            }
        }
    }
}
