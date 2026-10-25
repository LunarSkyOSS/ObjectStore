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
import java.util.Set;
import java.util.concurrent.Executors;

public final class FeatureTest {
    private static final String OWNER = "TESTACCESSKEY123";
    private static final String LIMITED = "LIMITEDACCESS1234";
    private static final String OWNER_SECRET = "test-secret-key-that-is-at-least-32-characters";
    private static final String LIMITED_SECRET = "limited-secret-key-that-is-at-least-32-characters";

    private static void status(int expected, HttpResponse<?> response) {
        if (response.statusCode() != expected)
            throw new AssertionError("Expected " + expected + ", got " + response.statusCode() + ": " + response.body());
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("objectstore-feature-test-");
        Path policy = root.resolve("scopes");
        Files.writeString(policy, LIMITED + "\tphotos\tusers/one/\tread,write,list\n");
        KeyScopes scopes = KeyScopes.fromEnvironment(Map.of("S3_KEY_SCOPES_FILE", policy.toString()),
            OWNER, Set.of(OWNER, LIMITED));
        scopes.require(LIMITED, "write", "photos", "users/one/image.jpg");
        scopes.checkBucket(LIMITED, "photos", "GET", Map.of("list-type", "2", "prefix", "users/one/"));
        denied(() -> scopes.require(LIMITED, "read", "photos", "users/two/image.jpg"));
        denied(() -> scopes.checkBucket(LIMITED, "photos", "GET", Map.of("list-type", "2")));
        denied(() -> scopes.checkBucket(LIMITED, "photos", "GET", Map.of("list-type", "2", "prefix", "users/")));
        denied(() -> scopes.checkObject(LIMITED, "photos", "users/one/copy.jpg", "PUT", Map.of(),
            headers("x-amz-copy-source", "/photos/users/two/private.jpg")));
        denied(() -> scopes.checkObject(LIMITED, "photos", "users/one/file", "PUT", Map.of(),
            headers("x-amz-acl", "public-read")));
        var cors = BrowserCors.fromEnvironment(Map.of("CORS_ALLOWED_ORIGINS", "https://dashboard.example"));
        var metrics = new RequestMetrics("metrics-test-token-at-least-32-characters");
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        DiskStore store = new DiskStore(root.resolve("data"), 1024, 4096);
        try {
            Main app = new Main(store, new MultipartStore(store),
                new SigV4(Map.of(OWNER, OWNER_SECRET, LIMITED, LIMITED_SECRET), OWNER,
                    "us-east-1", Clock.systemUTC()),
                "photos", ClientLimits.disabled(), 16, "", cors, scopes, metrics);
            server.setExecutor(executor);
            server.createContext("/", app::handle);
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            status(204, client.send(HttpRequest.newBuilder(URI.create(base + "/photos/file"))
                .header("Origin", "https://dashboard.example")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type,x-amz-date")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpRequest.newBuilder(URI.create(base + "/photos/file"))
                .header("Origin", "https://other.example")
                .header("Access-Control-Request-Method", "PUT")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()));
            var health = client.send(HttpRequest.newBuilder(URI.create(base + "/health"))
                .header("Origin", "https://dashboard.example").GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, health);
            if (!"https://dashboard.example".equals(health.headers().firstValue("Access-Control-Allow-Origin").orElse(null)))
                throw new AssertionError("Allowed origin missing from response");
            status(401, client.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            var snapshot = client.send(HttpRequest.newBuilder(URI.create(base + "/metrics"))
                .header("Authorization", "Bearer metrics-test-token-at-least-32-characters").GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, snapshot);
            if (!snapshot.body().contains("objectstore_requests_total 4"))
                throw new AssertionError("Metrics did not count requests: " + snapshot.body());
            status(200, client.send(HttpTest.signedUri(URI.create(base + "/photos?acl"),
                "PUT", new byte[0], Map.of("x-amz-grant-full-control", "id=\"" + LIMITED + "\"")),
                HttpResponse.BodyHandlers.ofString()));
            byte[] content = "allowed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            status(200, client.send(HttpTest.signedUriAs(URI.create(base + "/photos/users/one/allowed"),
                "PUT", content, Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(base + "/photos/users/two/denied"),
                "PUT", content, Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(HttpTest.signedUriAs(URI.create(base + "/photos?list-type=2&prefix=users%2Fone%2F"),
                "GET", new byte[0], Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(base + "/photos?list-type=2"),
                "GET", new byte[0], Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(base + "/photos/users/one/allowed?acl"),
                "GET", new byte[0], Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
        } finally {
            server.stop(0);
            executor.close();
            store.close();
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Feature tests passed: scoped keys, CORS, metrics");
    }

    private static com.sun.net.httpserver.Headers headers(String name, String value) {
        var headers = new com.sun.net.httpserver.Headers();
        headers.set(name, value);
        return headers;
    }

    private static void denied(Runnable action) {
        try { action.run(); }
        catch (StoreException error) {
            if (error.status == 403) return;
            throw new AssertionError("Wrong denial", error);
        }
        throw new AssertionError("Expected access denial");
    }
}
