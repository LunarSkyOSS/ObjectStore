package cloud.lunarsky.store;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;

public final class ClusterGc {
    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !args[0].equals("--apply")))
            throw new IllegalArgumentException("Usage: objectstore cluster-gc [--apply]");
        run(System.getenv(), args.length == 1);
    }

    static void run(Map<String, String> env, boolean apply) throws Exception {
        if (!"cluster".equals(env.get("STORE_MODE")) || !"true".equals(env.get("CLUSTER_LOCAL_DEV")))
            throw new IllegalArgumentException("Cluster garbage collection is only enabled in local cluster mode");
        boolean testDomains = "true".equals(env.get("CLUSTER_TEST_NODE_DOMAINS"));
        boolean disposableTest = testDomains && "true".equals(env.get("CLUSTER_GC_TEST_MODE"));
        long age = Long.parseLong(env.getOrDefault("CLUSTER_GC_MIN_AGE_SECONDS", "1209600"));
        long backupRetention = Long.parseLong(env.getOrDefault("CLUSTER_BACKUP_RETENTION_SECONDS", "0"));
        if (age < 0 || age > 315360000 || (age == 0 && !disposableTest))
            throw new IllegalArgumentException("Invalid garbage collection age");
        if (apply && !disposableTest && (backupRetention < 86400 || age <= backupRetention))
            throw new IllegalArgumentException("Set a garbage collection age longer than the backup retention");
        try (ClusterStore store = new ClusterStore(env.get("POSTGRES_JDBC_URL"), env.get("POSTGRES_USER"),
                env.get("POSTGRES_PASSWORD"), env.get("S3_BUCKET"),
                Arrays.stream(env.get("CLUSTER_NODES").split(",")).map(URI::create).toList(),
                env.get("CLUSTER_TOKEN"), env.get("CLUSTER_REPAIR_TOKEN"), 134217728, 2147483648L,
                testDomains)) {
            if (apply) {
                var repair = store.repairOnce();
                if (repair.underReplicated() > 0 || repair.unrecoverable() > 0)
                    throw new IllegalStateException("Refusing cleanup while live segments need repair");
            }
            var report = store.collectGarbage(age * 1000, apply);
            System.out.println("segments_scanned=" + report.scanned());
            System.out.println("orphan_candidates=" + report.eligible());
            System.out.println("segments_deleted=" + report.deleted());
            System.out.println("unavailable_nodes=" + report.unavailableNodes());
            if (report.unavailableNodes() > 0) throw new IllegalStateException("Cleanup did not scan every node");
        }
    }
}
