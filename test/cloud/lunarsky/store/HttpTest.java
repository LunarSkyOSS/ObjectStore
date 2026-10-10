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
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;

public final class HttpTest {
    private static final String ACCESS = "TESTACCESSKEY123";
    private static final String SECRET = "test-secret-key-that-is-at-least-32-characters";
    private static final String REGION = "us-east-1";
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static HttpRequest signed(String base, String method, String key, byte[] body) {
        return signedUri(URI.create(base + "/objects/" + SigV4.encode(key, true)), method, body, Map.of());
    }

    private static HttpRequest signedUri(URI uri, String method, byte[] body, Map<String, String> extra) {
        String host = uri.getAuthority();
        String date = DATE.format(Instant.now());
        String hash = SigV4.hex(SigV4.hash(body));
        TreeMap<String, String> signed = new TreeMap<>(extra);
        signed.put("host", host);
        signed.put("x-amz-content-sha256", hash);
        signed.put("x-amz-date", date);
        String names = String.join(";", signed.keySet());
        StringBuilder canonical = new StringBuilder(method).append('\n').append(uri.getRawPath()).append('\n')
            .append(SigV4.canonicalQuery(uri.getRawQuery())).append('\n');
        signed.forEach((name, value) -> canonical.append(name).append(':').append(value).append('\n'));
        canonical.append('\n').append(names).append('\n').append(hash);
        String scope = date.substring(0, 8) + "/" + REGION + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + date + "\n" + scope + "\n"
            + SigV4.hex(SigV4.hash(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        String signature = SigV4.hex(SigV4.hmac(
            SigV4.signingKey(SECRET, date.substring(0, 8), REGION), toSign));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .header("x-amz-date", date)
            .header("x-amz-content-sha256", hash)
            .header("authorization", "AWS4-HMAC-SHA256 Credential=" + ACCESS + "/"
                + scope + ",SignedHeaders=" + names + ",Signature=" + signature);
        extra.forEach(request::header);
        return request.method(method, body.length == 0
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

    private static void testObjects(HttpClient client, String base) throws Exception {
        status(200, client.send(HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()));
        String key = "folder/moon-☾.txt";
        byte[] body = "independent storage test".getBytes(StandardCharsets.UTF_8);
        status(403, client.send(HttpRequest.newBuilder(URI.create(base + "/objects/" + key))
            .GET().build(), HttpResponse.BodyHandlers.ofByteArray()));
        status(200, client.send(signed(base, "PUT", key, body),
            HttpResponse.BodyHandlers.ofByteArray()));
        String other = "folder/stars.txt";
        status(200, client.send(signedUri(URI.create(base + "/objects/" + other), "PUT",
            "stars".getBytes(StandardCharsets.UTF_8), Map.of("content-type", "text/plain")),
            HttpResponse.BodyHandlers.ofByteArray()));
        var get = client.send(signed(base, "GET", key, new byte[0]),
            HttpResponse.BodyHandlers.ofByteArray());
        status(200, get);
        if (!java.util.Arrays.equals(body, get.body())) throw new AssertionError("GET body mismatch");
        if (!"application/octet-stream".equals(get.headers().firstValue("content-type").orElse("")))
            throw new AssertionError("Unexpected content type");
        var typed = client.send(signed(base, "GET", other, new byte[0]), HttpResponse.BodyHandlers.ofByteArray());
        status(200, typed);
        if (!"text/plain".equals(typed.headers().firstValue("content-type").orElse("")))
            throw new AssertionError("Stored content type missing");
        var partial = client.send(signedUri(URI.create(base + "/objects/" + other), "GET",
            new byte[0], Map.of("range", "bytes=1-3")), HttpResponse.BodyHandlers.ofByteArray());
        status(206, partial);
        if (!"tar".equals(new String(partial.body(), StandardCharsets.UTF_8)) ||
            !"bytes 1-3/5".equals(partial.headers().firstValue("content-range").orElse("")))
            throw new AssertionError("Range response mismatch");
        status(416, client.send(signedUri(URI.create(base + "/objects/" + other), "GET",
            new byte[0], Map.of("range", "bytes=20-30")), HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static void testListing(HttpClient client, String base) throws Exception {
        var listed = client.send(signedUri(URI.create(base + "/objects?list-type=2&prefix=folder%2F"),
            "GET", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString());
        if (listed.statusCode() != 200 || !listed.body().contains("<Key>folder/stars.txt</Key>") ||
            !listed.body().contains("<Key>folder/moon-☾.txt</Key>"))
            throw new AssertionError("ListObjectsV2 failed: " + listed.body());
        var page = client.send(signedUri(URI.create(base + "/objects?list-type=2&max-keys=1"),
            "GET", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString());
        if (page.statusCode() != 200 || !page.body().contains("<IsTruncated>true</IsTruncated>"))
            throw new AssertionError("List pagination failed: " + page.body());
        String token = page.body().split("<NextContinuationToken>")[1].split("</NextContinuationToken>")[0];
        var next = client.send(signedUri(URI.create(base + "/objects?list-type=2&continuation-token=" + token),
            "GET", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString());
        if (next.statusCode() != 200 || !next.body().contains("<Key>folder/stars.txt</Key>"))
            throw new AssertionError("List continuation failed: " + next.body());
        var grouped = client.send(signedUri(URI.create(base + "/objects?list-type=2&delimiter=%2F"),
            "GET", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString());
        if (grouped.statusCode() != 200 || !grouped.body().contains("<CommonPrefixes><Prefix>folder/</Prefix></CommonPrefixes>") ||
            grouped.body().contains("<Contents>"))
            throw new AssertionError("Delimiter listing failed: " + grouped.body());
        var encoded = client.send(signedUri(URI.create(base + "/objects?list-type=2&encoding-type=url"),
            "GET", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString());
        if (encoded.statusCode() != 200 || !encoded.body().contains("folder%2Fmoon-%E2%98%BE.txt"))
            throw new AssertionError("Encoded listing failed: " + encoded.body());
        var emptyPage = client.send(signedUri(URI.create(base + "/objects?list-type=2&max-keys=0"),
            "GET", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString());
        if (emptyPage.statusCode() != 200 || !emptyPage.body().contains("<KeyCount>0</KeyCount>"))
            throw new AssertionError("Empty list page failed: " + emptyPage.body());
    }

    private static void testMultipart(HttpClient client, String base) throws Exception {
        String movie = "folder/video.mp4";
        URI initiate = URI.create(base + "/objects/" + movie + "?uploads=");
        var created = client.send(signedUri(initiate, "POST", new byte[0],
            Map.of("content-type", "video/mp4")), HttpResponse.BodyHandlers.ofString());
        if (created.statusCode() != 200) throw new AssertionError("Multipart initiation failed: " + created.body());
        String upload = created.body().split("<UploadId>")[1].split("</UploadId>")[0];
        byte[] first = "hello ".getBytes(StandardCharsets.UTF_8);
        byte[] second = "world".getBytes(StandardCharsets.UTF_8);
        var partOne = client.send(signedUri(URI.create(base + "/objects/" + movie +
            "?partNumber=1&uploadId=" + upload), "PUT", first, Map.of()), HttpResponse.BodyHandlers.ofByteArray());
        var partTwo = client.send(signedUri(URI.create(base + "/objects/" + movie +
            "?partNumber=2&uploadId=" + upload), "PUT", second, Map.of()), HttpResponse.BodyHandlers.ofByteArray());
        status(200, partOne); status(200, partTwo);
        String completion = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" +
            partOne.headers().firstValue("etag").orElseThrow() +
            "</ETag></Part><Part><PartNumber>2</PartNumber><ETag>" +
            partTwo.headers().firstValue("etag").orElseThrow() +
            "</ETag></Part></CompleteMultipartUpload>";
        status(200, client.send(signedUri(URI.create(base + "/objects/" + movie + "?uploadId=" + upload),
            "POST", completion.getBytes(StandardCharsets.UTF_8), Map.of()), HttpResponse.BodyHandlers.ofByteArray()));
        var assembled = client.send(signed(base, "GET", movie, new byte[0]), HttpResponse.BodyHandlers.ofByteArray());
        status(200, assembled);
        if (!"hello world".equals(new String(assembled.body(), StandardCharsets.UTF_8)) ||
            !"video/mp4".equals(assembled.headers().firstValue("content-type").orElse("")))
            throw new AssertionError("Completed multipart object mismatch");
        var abandoned = client.send(signedUri(URI.create(base + "/objects/abandoned?uploads="),
            "POST", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString());
        String abandonedId = abandoned.body().split("<UploadId>")[1].split("</UploadId>")[0];
        status(204, client.send(signedUri(URI.create(base + "/objects/abandoned?uploadId=" + abandonedId),
            "DELETE", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static void testDelete(HttpClient client, String base) throws Exception {
        String key = "folder/moon-☾.txt";
        var head = client.send(signed(base, "HEAD", key, new byte[0]),
            HttpResponse.BodyHandlers.ofByteArray());
        status(200, head);
        if (head.body().length != 0) throw new AssertionError("HEAD returned a body");
        status(204, client.send(signed(base, "DELETE", key, new byte[0]),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(404, client.send(signed(base, "GET", key, new byte[0]),
            HttpResponse.BodyHandlers.ofByteArray()));
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
            testObjects(client, base);
            testListing(client, base);
            testMultipart(client, base);
            testDelete(client, base);
            System.out.println("HTTP tests passed: health, authentication, PUT, GET, HEAD, DELETE, MIME, ranges, listing, multipart");
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
