package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ClusterTlsTest {
    private static final String PASSWORD = "local-test-password-0123456789";

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("objectstore-tls-");
        Path keyStore = directory.resolve("node.p12");
        Path trustStore = directory.resolve("trust.p12");
        Path certificate = directory.resolve("node.crt");
        Path passwordFile = directory.resolve("password");
        Files.writeString(passwordFile, PASSWORD + "\n");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        run(keytool, "-genkeypair", "-alias", "node", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=DNS:localhost",
            "-storetype", "PKCS12", "-keystore", keyStore.toString(), "-storepass", PASSWORD,
            "-keypass", PASSWORD, "-noprompt");
        run(keytool, "-exportcert", "-alias", "node", "-keystore", keyStore.toString(),
            "-storepass", PASSWORD, "-file", certificate.toString());
        run(keytool, "-importcert", "-alias", "node", "-file", certificate.toString(),
            "-keystore", trustStore.toString(), "-storetype", "PKCS12", "-storepass", PASSWORD,
            "-noprompt");

        Map<String, String> serverConfig = Map.of(
            "NODE_TLS_KEYSTORE", keyStore.toString(), "NODE_TLS_PASSWORD_FILE", passwordFile.toString());
        Map<String, String> clientConfig = Map.of(
            "CLUSTER_TLS_TRUSTSTORE", trustStore.toString(),
            "CLUSTER_TLS_PASSWORD_FILE", passwordFile.toString());
        String token = "tls-test-cluster-token-0123456789";
        String repairToken = "tls-test-repair-token-0123456789";
        UUID hostId = UUID.randomUUID();
        byte[] data = "encrypted transport".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (ClusterNode node = new ClusterNode(directory.resolve("data"), token, repairToken, hostId)) {
            HttpServer server = ClusterTls.nodeServer(new InetSocketAddress("127.0.0.1", 0), serverConfig);
            require(server instanceof HttpsServer, "Node did not enable HTTPS");
            server.createContext("/", node::handle);
            server.start();
            try {
                URI url = URI.create("https://localhost:" + server.getAddress().getPort());
                HttpClient trusted = ClusterTls.client(clientConfig, Duration.ofSeconds(3));
                NodeIdentity identity = NodeClient.probe(url, token, trusted);
                require(identity.hostId().equals(hostId), "TLS probe returned wrong node identity");
                NodeClient client = new NodeClient(List.of(
                    new NodeClient.Node(identity.nodeId(), hostId, url)), token, repairToken, trusted);
                UUID segment = UUID.randomUUID();
                client.put(0, segment, data, SigV4.hash(data));
                require(java.util.Arrays.equals(data, client.get(0, segment, data.length, SigV4.hash(data))),
                    "TLS segment roundtrip failed");
                HttpRequest request = HttpRequest.newBuilder(url.resolve("/identity"))
                    .header("X-Cluster-Token", token).GET().build();
                try {
                    ClusterTls.client(Map.of(), Duration.ofSeconds(3))
                        .send(request, HttpResponse.BodyHandlers.discarding());
                    throw new AssertionError("Untrusted certificate was accepted");
                } catch (IOException expected) { }
                URI wrongHost = URI.create("https://127.0.0.1:" + server.getAddress().getPort());
                try {
                    NodeClient.probe(wrongHost, token, trusted);
                    throw new AssertionError("Wrong certificate hostname was accepted");
                } catch (IOException expected) { }
            } finally {
                server.stop(0);
            }
        }
        try {
            ClusterTls.nodeServer(new InetSocketAddress("127.0.0.1", 0),
                Map.of("NODE_TLS_KEYSTORE", keyStore.toString()));
            throw new AssertionError("Incomplete TLS configuration was accepted");
        } catch (IOException expected) { }
        try {
            ClusterTls.client(Map.of("CLUSTER_TLS_TRUSTSTORE", trustStore.toString()),
                Duration.ofSeconds(3));
            throw new AssertionError("Incomplete cluster trust configuration was accepted");
        } catch (IOException expected) { }
        try {
            NodeClient.validateUrl(URI.create("http://localhost:9100"), true);
            throw new AssertionError("HTTP node URL was accepted with cluster TLS enabled");
        } catch (IllegalArgumentException expected) { }
        System.out.println("Cluster TLS tests passed: trusted roundtrip, untrusted and hostname rejection, no HTTP downgrade");
    }

    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new AssertionError("keytool failed: " + output);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
