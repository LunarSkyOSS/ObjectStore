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
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

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

    private static void testCopy(HttpClient client, String base) throws Exception {
        String source = "folder/copy source.txt";
        String target = "folder/copied.txt";
        byte[] body = "copy source".getBytes(StandardCharsets.UTF_8);
        status(200, client.send(signedUri(URI.create(base + "/objects/" + SigV4.encode(source, true)),
            "PUT", body, Map.of("content-type", "text/plain")), HttpResponse.BodyHandlers.ofByteArray()));
        String header = "/objects/" + SigV4.encode(source, true);
        var result = client.send(signedUri(URI.create(base + "/objects/" + target), "PUT",
            new byte[0], Map.of("x-amz-copy-source", header)), HttpResponse.BodyHandlers.ofString());
        if (result.statusCode() != 200 || !result.body().contains("<CopyObjectResult>") ||
            !result.body().contains("<ETag>&quot;"))
            throw new AssertionError("CopyObject failed: " + result.body());
        var copied = client.send(signed(base, "GET", target, new byte[0]), HttpResponse.BodyHandlers.ofByteArray());
        status(200, copied);
        if (!java.util.Arrays.equals(body, copied.body()) ||
            !"text/plain".equals(copied.headers().firstValue("content-type").orElse("")))
            throw new AssertionError("Copied body or content type mismatch");
        status(200, client.send(signedUri(URI.create(base + "/objects/" + target), "PUT",
            new byte[0], Map.of("x-amz-copy-source", header, "x-amz-metadata-directive", "REPLACE",
                "content-type", "text/markdown")), HttpResponse.BodyHandlers.ofByteArray()));
        var replaced = client.send(signed(base, "GET", target, new byte[0]), HttpResponse.BodyHandlers.ofByteArray());
        status(200, replaced);
        if (!java.util.Arrays.equals(body, replaced.body()) ||
            !"text/markdown".equals(replaced.headers().firstValue("content-type").orElse("")))
            throw new AssertionError("REPLACE metadata directive failed");
        status(200, client.send(signedUri(URI.create(base + "/objects/" + target), "PUT",
            new byte[0], Map.of("x-amz-copy-source", "/objects/" + target)),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(404, client.send(signedUri(URI.create(base + "/objects/" + target), "PUT",
            new byte[0], Map.of("x-amz-copy-source", "/objects/missing")),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(404, client.send(signedUri(URI.create(base + "/objects/" + target), "PUT",
            new byte[0], Map.of("x-amz-copy-source", "/other/source")),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(400, client.send(signedUri(URI.create(base + "/objects/" + target), "PUT",
            new byte[0], Map.of("x-amz-copy-source", "/objects/source?versionId=1")),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(501, client.send(signedUri(URI.create(base + "/objects/" + target), "PUT",
            new byte[0], Map.of("x-amz-copy-source", header, "x-amz-metadata-directive", "REPLACE",
                "content-md5", "AAAAAAAAAAAAAAAAAAAAAA==")), HttpResponse.BodyHandlers.ofByteArray()));
        status(204, client.send(signed(base, "DELETE", source, new byte[0]),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(204, client.send(signed(base, "DELETE", target, new byte[0]),
            HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static String encodedChecksum(String algorithm, byte[] body) throws Exception {
        if (algorithm.startsWith("CRC")) {
            Checksum checksum = algorithm.equals("CRC32") ? new CRC32() : new CRC32C();
            checksum.update(body, 0, body.length);
            long value = checksum.getValue();
            return Base64.getEncoder().encodeToString(new byte[]{(byte) (value >>> 24),
                (byte) (value >>> 16), (byte) (value >>> 8), (byte) value});
        }
        String name = algorithm.equals("SHA1") ? "SHA-1" :
            algorithm.equals("SHA256") ? "SHA-256" :
            algorithm.equals("SHA512") ? "SHA-512" : "MD5";
        return Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance(name).digest(body));
    }

    private static void testChecksums(HttpClient client, String base) throws Exception {
        URI uri = URI.create(base + "/objects/checksum-target");
        byte[] body = "checksum payload".getBytes(StandardCharsets.UTF_8);
        String md5 = encodedChecksum("MD5", body);
        for (String algorithm : new String[]{"CRC32", "CRC32C", "SHA1", "SHA256", "SHA512", "MD5"}) {
            String header = "x-amz-checksum-" + algorithm.toLowerCase(java.util.Locale.ROOT);
            String checksum = encodedChecksum(algorithm, body);
            var stored = client.send(signedUri(uri, "PUT", body,
                Map.of("content-md5", md5, header, checksum, "x-amz-sdk-checksum-algorithm", algorithm)),
                HttpResponse.BodyHandlers.ofByteArray());
            status(200, stored);
            if (!checksum.equals(stored.headers().firstValue(header).orElse("")))
                throw new AssertionError("Missing checksum response: " + algorithm);
            var bad = client.send(signedUri(uri, "PUT", body,
                Map.of(header, Base64.getEncoder().encodeToString(new byte[algorithm.startsWith("CRC") ? 4 :
                    algorithm.equals("SHA1") ? 20 : algorithm.equals("SHA256") ? 32 :
                    algorithm.equals("SHA512") ? 64 : 16]))), HttpResponse.BodyHandlers.ofString());
            if (bad.statusCode() != 400 || !bad.body().contains("BadDigest"))
                throw new AssertionError("Mismatched " + algorithm + " accepted: " + bad.body());
            var unchanged = client.send(signedUri(uri, "GET", new byte[0], Map.of()),
                HttpResponse.BodyHandlers.ofByteArray());
            status(200, unchanged);
            if (!java.util.Arrays.equals(body, unchanged.body()))
                throw new AssertionError("Bad checksum replaced stored object");
        }
        var badMd5 = client.send(signedUri(uri, "PUT", body,
            Map.of("content-md5", "AAAAAAAAAAAAAAAAAAAAAA==")), HttpResponse.BodyHandlers.ofString());
        if (badMd5.statusCode() != 400 || !badMd5.body().contains("BadDigest"))
            throw new AssertionError("Mismatched Content-MD5 accepted");
        status(400, client.send(signedUri(uri, "PUT", body,
            Map.of("content-md5", "invalid")), HttpResponse.BodyHandlers.ofByteArray()));
        status(400, client.send(signedUri(uri, "PUT", body,
            Map.of("x-amz-checksum-crc32", encodedChecksum("CRC32", body),
                "x-amz-sdk-checksum-algorithm", "CRC32C")), HttpResponse.BodyHandlers.ofByteArray()));
        status(501, client.send(signedUri(uri, "PUT", body,
            Map.of("x-amz-checksum-crc64nvme", "AAAAAAAAAAA=")), HttpResponse.BodyHandlers.ofByteArray()));
        status(204, client.send(signedUri(uri, "DELETE", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
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
        status(200, partOne);
        status(200, partTwo);
        var rejectedPart = client.send(signedUri(URI.create(base + "/objects/" + movie +
            "?partNumber=1&uploadId=" + upload), "PUT", first,
            Map.of("content-md5", "AAAAAAAAAAAAAAAAAAAAAA==")), HttpResponse.BodyHandlers.ofString());
        if (rejectedPart.statusCode() != 400 || !rejectedPart.body().contains("BadDigest"))
            throw new AssertionError("Mismatched part Content-MD5 accepted");
        var parts = client.send(signedUri(URI.create(base + "/objects/" + movie +
            "?uploadId=" + upload + "&max-parts=1"), "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofString());
        if (parts.statusCode() != 200 || !parts.body().contains("<IsTruncated>true</IsTruncated>") ||
            !parts.body().contains("<PartNumber>1</PartNumber>"))
            throw new AssertionError("Multipart part listing failed: " + parts.body());
        var uploads = client.send(signedUri(URI.create(base + "/objects?uploads&prefix=folder%2F"),
            "GET", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString());
        if (uploads.statusCode() != 200 || !uploads.body().contains("<UploadId>" + upload + "</UploadId>"))
            throw new AssertionError("Multipart upload listing failed: " + uploads.body());
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
        status(404, client.send(signedUri(URI.create(base + "/objects/" + movie + "?uploadId=" + upload),
            "GET", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofByteArray()));
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
            testCopy(client, base);
            testChecksums(client, base);
            testMultipart(client, base);
            testDelete(client, base);
            System.out.println("HTTP tests passed: objects, copy, checksums, listing, multipart");
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
