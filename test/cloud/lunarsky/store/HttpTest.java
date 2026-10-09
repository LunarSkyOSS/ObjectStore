package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.Executors;

public final class HttpTest {
    private static final String ACCESS = "TESTACCESSKEY123";
    private static final String SECRET = "test-secret-key-that-is-at-least-32-characters";
    private static final String REGION = "us-east-1";
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static HttpRequest signed(String base, String method, String key, byte[] body) {
        URI uri = URI.create(base + "/objects/" + SigV4.encode(key, true));
        String host = uri.getAuthority();
        String date = DATE.format(Instant.now());
        String hash = SigV4.hex(SigV4.hash(body));
        String names = "host;x-amz-content-sha256;x-amz-date";
        String canonical = method + "\n" + uri.getRawPath() + "\n\n"
            + "host:" + host + "\n"
            + "x-amz-content-sha256:" + hash + "\n"
            + "x-amz-date:" + date + "\n\n" + names + "\n" + hash;
        String scope = date.substring(0, 8) + "/" + REGION + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + date + "\n" + scope + "\n"
            + SigV4.hex(SigV4.hash(canonical.getBytes(StandardCharsets.UTF_8)));
        String signature = SigV4.hex(SigV4.hmac(
            SigV4.signingKey(SECRET, date.substring(0, 8), REGION), toSign));
        return HttpRequest.newBuilder(uri)
            .header("x-amz-date", date)
            .header("x-amz-content-sha256", hash)
            .header("authorization", "AWS4-HMAC-SHA256 Credential=" + ACCESS + "/"
                + scope + ",SignedHeaders=" + names + ",Signature=" + signature)
            .method(method, body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    }

    private static void status(int expected, HttpResponse<byte[]> response) {
        if (response.statusCode() != expected) {
            throw new AssertionError("Expected HTTP " + expected + ", got " + response.statusCode()
                + ": " + new String(response.body(), StandardCharsets.UTF_8));
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("store-http-test-");
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        DiskStore store = new DiskStore(root, 1024, 4096);
        try {
            var app = new Main(store,
                new SigV4(ACCESS, SECRET, REGION, Clock.systemUTC()), "objects");
            server.setExecutor(executor);
            server.createContext("/", app::handle);
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            status(200, client.send(HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()));
            String key = "folder/moon-☾.txt";
            byte[] body = "independent storage test".getBytes(StandardCharsets.UTF_8);
            status(403, client.send(HttpRequest.newBuilder(URI.create(base + "/objects/" + key))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray()));
            status(200, client.send(signed(base, "PUT", key, body),
                HttpResponse.BodyHandlers.ofByteArray()));
            var get = client.send(signed(base, "GET", key, new byte[0]),
                HttpResponse.BodyHandlers.ofByteArray());
            status(200, get);
            if (!java.util.Arrays.equals(body, get.body())) throw new AssertionError("GET body mismatch");
            if (!"application/octet-stream".equals(get.headers().firstValue("content-type").orElse("")))
                throw new AssertionError("Unexpected content type");
            var head = client.send(signed(base, "HEAD", key, new byte[0]),
                HttpResponse.BodyHandlers.ofByteArray());
            status(200, head);
            if (head.body().length != 0) throw new AssertionError("HEAD returned a body");
            status(204, client.send(signed(base, "DELETE", key, new byte[0]),
                HttpResponse.BodyHandlers.ofByteArray()));
            status(404, client.send(signed(base, "GET", key, new byte[0]),
                HttpResponse.BodyHandlers.ofByteArray()));
            System.out.println("HTTP tests passed: health, authentication, PUT, GET, HEAD, DELETE");
        } finally {
            server.stop(0);
            executor.close();
            store.close();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
