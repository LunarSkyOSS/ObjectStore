package cloud.lunarsky.store;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;

public final class ClusterRepair {
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Usage: objectstore cluster-repair");
        Map<String, String> env = System.getenv();
        if (!"cluster".equals(env.get("STORE_MODE")) || !"true".equals(env.get("CLUSTER_LOCAL_DEV")))
            throw new IllegalArgumentException("Cluster repair is only enabled in local cluster mode");
        try (ClusterStore store = new ClusterStore(env.get("POSTGRES_JDBC_URL"), env.get("POSTGRES_USER"),
                env.get("POSTGRES_PASSWORD"), env.get("S3_BUCKET"),
                Arrays.stream(env.get("CLUSTER_NODES").split(",")).map(URI::create).toList(),
                env.get("CLUSTER_TOKEN"), env.get("CLUSTER_REPAIR_TOKEN"), 134217728, 2147483648L,
                "true".equals(env.get("CLUSTER_TEST_NODE_DOMAINS")))) {
            var report = store.repairOnce();
            System.out.println("segments_scanned=" + report.scanned());
            System.out.println("replicas_restored=" + report.restored());
            System.out.println("segments_under_replicated=" + report.underReplicated());
            System.out.println("segments_unrecoverable=" + report.unrecoverable());
            if (report.unrecoverable() > 0) System.exit(1);
        }
    }
}
