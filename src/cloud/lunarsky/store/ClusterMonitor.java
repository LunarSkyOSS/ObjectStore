package cloud.lunarsky.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static cloud.lunarsky.store.ClusterStore.*;

final class ClusterMonitor {
    @FunctionalInterface
    interface Connections { Connection open() throws SQLException; }
    private final Connections connections;
    private final NodeClient nodes;
    private volatile ClusterStatus cachedStatus;
    private volatile long statusUntil;

    ClusterMonitor(Connections connections, NodeClient nodes) {
        this.connections = connections;
        this.nodes = nodes;
    }

    synchronized ClusterStatus status() {
        long now = System.nanoTime();
        ClusterStatus cached = cachedStatus;
        if (cached != null && now < statusUntil) return cached;
        boolean writable = false;
        RepairStatus repair = null;
        Map<UUID, Long> verifiedReplicas = new java.util.HashMap<>();
        try (Connection connection = connections.open(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (ResultSet result = statement.executeQuery(
                    "SELECT NOT pg_is_in_recovery() AND current_setting('transaction_read_only') = 'off'")) {
                writable = result.next() && result.getBoolean(1);
            }
            try (ResultSet result = statement.executeQuery("SELECT started_at, updated_at, finished_at, state, " +
                    "scanned, restored, rebalanced, under_replicated, unrecoverable " +
                    "FROM cluster_repair_status WHERE singleton=1")) {
                if (result.next()) repair = new RepairStatus(result.getLong(1), result.getLong(2),
                    result.getLong(3), result.getString(4),
                    new RepairReport(result.getInt(5), result.getInt(6), result.getInt(7),
                        result.getInt(8), result.getInt(9)));
            }
            if (repair != null && repair.state().equals("completed")) {
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT node_id, verified_replicas FROM cluster_repair_node_counts " +
                        "WHERE finished_at >= ?")) {
                    query.setLong(1, repair.startedAt());
                    try (ResultSet result = query.executeQuery()) {
                        while (result.next())
                            verifiedReplicas.put((UUID) result.getObject(1), result.getLong(2));
                    }
                }
            }
            connection.commit();
        } catch (SQLException unavailable) { writable = false; }
        List<NodeClient.Node> configured = nodes.nodes();
        int visible = Math.min(64, configured.size());
        List<NodeStatus> live = new ArrayList<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Semaphore slots = new java.util.concurrent.Semaphore(16);
            List<java.util.concurrent.Future<NodeStatus>> checks = new ArrayList<>();
            for (int i = 0; i < visible; i++) {
                NodeClient.Node node = configured.get(i);
                checks.add(executor.submit(() -> {
                    slots.acquireUninterruptibly();
                    try { return new NodeStatus(node, nodes.reachable(node)); }
                    finally { slots.release(); }
                }));
            }
            for (var check : checks) {
                try { live.add(check.get()); }
                catch (Exception error) { live.add(new NodeStatus(configured.get(live.size()), false)); }
            }
        }
        ClusterStatus snapshot = new ClusterStatus(writable, List.copyOf(live),
            configured.size() - visible, repair, Map.copyOf(verifiedReplicas),
            System.currentTimeMillis());
        cachedStatus = snapshot;
        statusUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        return snapshot;
    }
    void completeRepair(long startedAt, RepairReport report, Map<UUID, Long> counts) {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            long now = System.currentTimeMillis();
            try (var clear = connection.createStatement()) {
                clear.executeUpdate("DELETE FROM cluster_repair_node_counts");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO cluster_repair_node_counts VALUES (?, ?, ?)")) {
                for (var entry : counts.entrySet()) {
                    insert.setObject(1, entry.getKey());
                    insert.setLong(2, entry.getValue());
                    insert.setLong(3, now);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            writeRepairStatus(connection, "completed", startedAt, now, report);
            connection.commit();
            cachedStatus = null;
        } catch (SQLException error) {
            System.err.println("Cluster repair summary could not be saved: " + error.getMessage());
        }
    }

    void recordRepairStatus(String state, long startedAt, RepairReport report) {
        try (Connection connection = connections.open()) {
            writeRepairStatus(connection, state, startedAt, System.currentTimeMillis(), report);
            cachedStatus = null;
        } catch (SQLException error) {
            System.err.println("Cluster repair status could not be saved: " + error.getMessage());
        }
    }

    private void writeRepairStatus(Connection connection, String state, long startedAt, long now,
                                   RepairReport report) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "INSERT INTO cluster_repair_status (singleton, started_at, updated_at, finished_at, state, " +
                "scanned, restored, rebalanced, under_replicated, unrecoverable) " +
                "VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (singleton) DO UPDATE SET " +
                "started_at=EXCLUDED.started_at, updated_at=EXCLUDED.updated_at, " +
                "finished_at=EXCLUDED.finished_at, state=EXCLUDED.state, " +
                "scanned=EXCLUDED.scanned, restored=EXCLUDED.restored, " +
                "rebalanced=EXCLUDED.rebalanced, under_replicated=EXCLUDED.under_replicated, " +
                "unrecoverable=EXCLUDED.unrecoverable")) {
            update.setLong(1, startedAt);
            update.setLong(2, now);
            update.setLong(3, state.equals("running") ? 0 : now);
            update.setString(4, state);
            update.setInt(5, report.scanned());
            update.setInt(6, report.restored());
            update.setInt(7, report.rebalanced());
            update.setInt(8, report.underReplicated());
            update.setInt(9, report.unrecoverable());
            update.executeUpdate();
        }
    }

}
