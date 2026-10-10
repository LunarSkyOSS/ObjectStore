package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.Executors;

public final class ClientLimitsTest {
    private static final String ACCESS = "TESTACCESSKEY123";
    private static final String SECRET = "test-secret-key-that-is-at-least-32-characters";

    private static HttpResponse<String> get(HttpClient client, String base, String path, String ip) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path));
        if (ip != null) request.header("X-Real-IP", ip);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void status(int wanted, HttpResponse<?> response) {
        if (response.statusCode() != wanted)
            throw new AssertionError("Expected " + wanted + ", got " + response.statusCode() + ": " + response.body());
    }

    private static void exercise(Map<String, String> configuration, boolean trusted) throws Exception {
        Path root = Files.createTempDirectory("client-limits-test-");
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        DiskStore store = new DiskStore(root, 1024, 4096);
        try {
            var app = new Main(store, new MultipartStore(store),
                new SigV4(Map.of(ACCESS, SECRET), ACCESS, "us-east-1", Clock.systemUTC()),
                "objects", ClientLimits.fromEnvironment(configuration));
            server.setExecutor(executor);
            server.createContext("/", app::handle);
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            if (trusted) {
                status(200, get(client, base, "/health", null));
                status(400, get(client, base, "/ready", null));
                status(400, get(client, base, "/objects/example", null));
                status(400, get(client, base, "/health", "not-an-ip"));
                status(400, client.send(HttpRequest.newBuilder(URI.create(base + "/health"))
                    .header("X-Real-IP", "192.0.2.1")
                    .header("X-Real-IP", "192.0.2.2")
                    .GET().build(), HttpResponse.BodyHandlers.ofString()));
                status(200, get(client, base, "/health", "192.0.2.1"));
                var limited = get(client, base, "/health", "192.0.2.1");
                status(503, limited);
                if (!"1".equals(limited.headers().firstValue("Retry-After").orElse(null)))
                    throw new AssertionError("SlowDown response lacks Retry-After");
                status(200, get(client, base, "/health", "192.0.2.2"));
            } else if (configuration.containsKey("PUBLIC_BYTES_PER_SECOND")) {
                long start = System.nanoTime();
                byte[] upload = new byte[128];
                status(200, client.send(HttpTest.signedUri(URI.create(base + "/objects/bandwidth"),
                    "PUT", upload, Map.of()), HttpResponse.BodyHandlers.ofString()));
                if (System.nanoTime() - start < 800_000_000L)
                    throw new AssertionError("Upload bytes were not paced");
                start = System.nanoTime();
                status(200, get(client, base, "/health", "192.0.2.1"));
                status(200, get(client, base, "/health", "192.0.2.1"));
                if (System.nanoTime() - start < 250_000_000L)
                    throw new AssertionError("Responses were not paced by the shared byte budget");
            } else {
                status(200, get(client, base, "/health", "192.0.2.1"));
                status(503, get(client, base, "/health", "192.0.2.2"));
            }
        } finally {
            server.stop(0);
            executor.close();
            store.close();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        var disabled = ClientLimits.fromEnvironment(Map.of());
        if (disabled == null) throw new AssertionError("Disabled configuration missing");
        try { ClientLimits.fromEnvironment(Map.of("PUBLIC_TRUSTED_PROXY_IPS", "127.0.0.1"));
            throw new AssertionError("Proxy trust accepted without limits");
        } catch (IllegalArgumentException expected) { }
        exercise(Map.of("PUBLIC_REQUESTS_PER_SECOND", "1", "PUBLIC_REQUEST_BURST", "1",
            "PUBLIC_TRUSTED_PROXY_IPS", "127.0.0.1"), true);
        exercise(Map.of("PUBLIC_REQUESTS_PER_SECOND", "1", "PUBLIC_REQUEST_BURST", "1"), false);
        exercise(Map.of("PUBLIC_BYTES_PER_SECOND", "64", "PUBLIC_BYTE_BURST", "64"), false);
        System.out.println("Client limit tests passed");
    }
}
