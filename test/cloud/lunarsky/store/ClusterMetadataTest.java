package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ClusterMetadataTest {
    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        String token = "metadata-test-cluster-token-0123456789";
        String repairToken = "metadata-test-repair-token-0123456789";
        Path root = Files.createTempDirectory("objectstore-metadata-test-");
        try (ClusterNode first = new ClusterNode(root.resolve("first"), token, repairToken, UUID.randomUUID());
             ClusterNode second = new ClusterNode(root.resolve("second"), token, repairToken, UUID.randomUUID())) {
            HttpServer firstServer = server(first);
            HttpServer secondServer = server(second);
            try {
                List<URI> nodes = List.of(url(firstServer), url(secondServer));
                try (ClusterStore store = new ClusterStore(env.get("POSTGRES_JDBC_URL"),
                        env.get("POSTGRES_USER"), env.get("POSTGRES_PASSWORD"), "objects",
                        nodes, token, repairToken, 1024 * 1024, 16 * 1024 * 1024, false)) {
                    require(store.ready(), "Writable primary was not selected from the multi-host URL");
                    try (var admin = DriverManager.getConnection(env.get("POSTGRES_ADMIN_JDBC_URL"),
                            env.get("POSTGRES_USER"), env.get("POSTGRES_PASSWORD"));
                         var statement = admin.createStatement()) {
                        statement.execute("ALTER DATABASE objectstore_test SET default_transaction_read_only=on");
                        try {
                            require(!store.ready(), "Read-only metadata was reported ready");
                        } finally {
                            statement.execute("ALTER DATABASE objectstore_test RESET default_transaction_read_only");
                        }
                    }
                    require(store.ready(), "Writable metadata did not recover after read-only mode ended");
                }
            } finally {
                firstServer.stop(0);
                secondServer.stop(0);
            }
        }
        System.out.println("Metadata routing tests passed: second JDBC host and writable readiness");
    }

    private static HttpServer server(ClusterNode node) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", node::handle);
        server.start();
        return server;
    }

    private static URI url(HttpServer server) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
