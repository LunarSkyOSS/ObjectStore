package cloud.lunarsky.store;

import java.net.URI;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;

public final class ClusterJoin {
    private ClusterJoin() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("Usage: objectstore cluster-join node-url expected-host-uuid");
        Map<String, String> env = System.getenv();
        if (!"cluster".equals(env.get("STORE_MODE")) || !"true".equals(env.get("CLUSTER_LOCAL_DEV")))
            throw new IllegalArgumentException("Node registration is only enabled in local cluster mode");
        URI url = URI.create(args[0]);
        UUID host = UUID.fromString(args[1]);
        try (var connection = DriverManager.getConnection(env.get("POSTGRES_JDBC_URL"),
                env.get("POSTGRES_USER"), env.get("POSTGRES_PASSWORD"))) {
            if (SchemaMigrator.prepare(connection, env.get("S3_BUCKET")) != 2)
                throw new IllegalStateException("Migrate legacy replicas before joining nodes");
            NodeClient.Node node = NodeRegistry.join(connection, url, host, env.get("CLUSTER_TOKEN"));
            System.out.println("node_id=" + node.id());
            System.out.println("host_id=" + node.hostId());
            System.out.println("endpoint=" + node.url());
        }
    }
}
