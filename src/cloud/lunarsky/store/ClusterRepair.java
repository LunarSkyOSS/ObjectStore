package cloud.lunarsky.store;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;

public final class ClusterRepair {
    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !args[0].equals("--loop")))
            throw new IllegalArgumentException("Usage: objectstore cluster-repair [--loop]");
        Map<String, String> env = System.getenv();
        if (!"cluster".equals(env.get("STORE_MODE")) || !"true".equals(env.get("CLUSTER_LOCAL_DEV")))
            throw new IllegalArgumentException("Cluster repair is only enabled in local cluster mode");
        boolean loop = args.length == 1;
        long seconds = Long.parseLong(env.getOrDefault("CLUSTER_MAINTENANCE_INTERVAL_SECONDS", "60"));
        if (seconds < 1 || seconds > 3600) throw new IllegalArgumentException("Invalid maintenance interval");
        boolean gcEnabled = loop && "true".equals(env.get("CLUSTER_GC_ENABLED"));
        long gcInterval = Long.parseLong(env.getOrDefault("CLUSTER_GC_INTERVAL_SECONDS", "86400"));
        if (gcEnabled && (gcInterval < 1 || gcInterval > 604800))
            throw new IllegalArgumentException("Invalid garbage collection interval");
        long nextGc = 0;
        do {
            try (ClusterStore store = new ClusterStore(env.get("POSTGRES_JDBC_URL"), env.get("POSTGRES_USER"),
                    env.get("POSTGRES_PASSWORD"), env.get("S3_BUCKET"),
                    Arrays.stream(env.get("CLUSTER_NODES").split(",")).map(URI::create).toList(),
                    env.get("CLUSTER_TOKEN"), env.get("CLUSTER_REPAIR_TOKEN"), 134217728, 2147483648L,
                    "true".equals(env.get("CLUSTER_TEST_NODE_DOMAINS")))) {
                var report = store.repairOnce();
                System.out.println("segments_scanned=" + report.scanned());
                System.out.println("replicas_restored=" + report.restored());
                System.out.println("segments_rebalanced=" + report.rebalanced());
                System.out.println("segments_under_replicated=" + report.underReplicated());
                System.out.println("segments_unrecoverable=" + report.unrecoverable());
                if (!loop && report.unrecoverable() > 0) System.exit(1);
                if (gcEnabled && System.currentTimeMillis() >= nextGc) {
                    ClusterGc.run(env, true);
                    nextGc = System.currentTimeMillis() + gcInterval * 1000;
                }
            } catch (Exception error) {
                if (!loop) throw error;
                System.err.println("Cluster maintenance failed: " + error.getMessage());
            }
            if (loop) Thread.sleep(seconds * 1000);
        } while (loop);
    }
}
