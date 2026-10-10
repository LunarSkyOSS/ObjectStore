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
    private static final String SECONDARY = "SECONDARYKEY1234";
    private static final String SECONDARY_SECRET = "secondary-secret-key-that-is-at-least-32-characters";
    private static final String REGION = "us-east-1";
    private static final DateTimeFormatter DATE =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static HttpRequest signed(String base, String method, String key, byte[] body) {
        return signedUri(URI.create(base + "/objects/" + SigV4.encode(key, true)), method, body, Map.of());
    }

    static HttpRequest signedUri(URI uri, String method, byte[] body, Map<String, String> extra) {
        return signedUriAs(uri, method, body, extra, ACCESS, SECRET);
    }

    private static HttpRequest signedUriAs(URI uri, String method, byte[] body, Map<String, String> extra,
                                           String access, String secret) {
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
            SigV4.signingKey(secret, date.substring(0, 8), REGION), toSign));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .header("x-amz-date", date)
            .header("x-amz-content-sha256", hash)
            .header("authorization", "AWS4-HMAC-SHA256 Credential=" + access + "/"
                + scope + ",SignedHeaders=" + names + ",Signature=" + signature);
        extra.forEach(request::header);
        return request.method(method, body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    }

    private static URI presignedUri(URI uri, String method, int expires) {
        String date = DATE.format(Instant.now());
        String scope = date.substring(0, 8) + "/" + REGION + "/s3/aws4_request";
        String query = "X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Credential=" +
            SigV4.encode(ACCESS + "/" + scope, false) + "&X-Amz-Date=" + date +
            "&X-Amz-Expires=" + expires + "&X-Amz-SignedHeaders=host";
        String canonical = method + "\n" + uri.getRawPath() + "\n" +
            SigV4.canonicalQuery(query) + "\nhost:" + uri.getAuthority() +
            "\n\nhost\nUNSIGNED-PAYLOAD";
        String toSign = "AWS4-HMAC-SHA256\n" + date + "\n" + scope + "\n" +
            SigV4.hex(SigV4.hash(canonical.getBytes(StandardCharsets.UTF_8)));
        String signature = SigV4.hex(SigV4.hmac(SigV4.signingKey(SECRET,
            date.substring(0, 8), REGION), toSign));
        return URI.create(uri + "?" + query + "&X-Amz-Signature=" + signature);
    }

    private static void testPresigned(HttpClient client, String base) throws Exception {
        URI object = URI.create(base + "/objects/presigned-test");
        byte[] body = "presigned upload".getBytes(StandardCharsets.UTF_8);
        status(200, client.send(HttpRequest.newBuilder(presignedUri(object, "PUT", 60))
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
            HttpResponse.BodyHandlers.ofByteArray()));
        var read = client.send(HttpRequest.newBuilder(presignedUri(object, "GET", 60)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
        status(200, read);
        if (!java.util.Arrays.equals(body, read.body())) throw new AssertionError("Presigned object mismatch");
        URI tampered = URI.create(presignedUri(object, "GET", 60).toString().replace("presigned-test", "different"));
        status(403, client.send(HttpRequest.newBuilder(tampered).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(204, client.send(HttpRequest.newBuilder(presignedUri(object, "DELETE", 60))
            .DELETE().build(), HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static void testAcl(HttpClient client, String base) throws Exception {
        byte[] body = "private object".getBytes(StandardCharsets.UTF_8);
        URI object = URI.create(base + "/objects/acl-test");
        URI objectAcl = URI.create(object + "?acl");
        status(200, client.send(signedUri(object, "PUT", body, Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(403, client.send(signedUriAs(object, "GET", new byte[0], Map.of(),
            SECONDARY, SECONDARY_SECRET), HttpResponse.BodyHandlers.ofByteArray()));
        status(403, client.send(signedUriAs(object, "GET", new byte[0], Map.of(),
            SECONDARY, SECRET), HttpResponse.BodyHandlers.ofByteArray()));
        status(403, client.send(signedUriAs(URI.create(base + "/_objectstore/capabilities"),
            "GET", new byte[0], Map.of(), SECONDARY, SECONDARY_SECRET),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(403, client.send(HttpRequest.newBuilder(object).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(200, client.send(signedUri(objectAcl, "PUT", new byte[0],
            Map.of("x-amz-grant-read", "id=\"" + SECONDARY + "\"")),
            HttpResponse.BodyHandlers.ofByteArray()));
        var read = client.send(signedUriAs(object, "GET", new byte[0], Map.of(),
            SECONDARY, SECONDARY_SECRET), HttpResponse.BodyHandlers.ofByteArray());
        status(200, read);
        if (!java.util.Arrays.equals(body, read.body())) throw new AssertionError("ACL read mismatch");
        status(403, client.send(signedUriAs(object, "DELETE", new byte[0], Map.of(),
            SECONDARY, SECONDARY_SECRET), HttpResponse.BodyHandlers.ofByteArray()));
        var acl = client.send(signedUri(objectAcl, "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofString());
        status(200, acl);
        if (!acl.body().contains(SECONDARY)) throw new AssertionError("Object ACL grant missing");
        status(200, client.send(signedUri(URI.create(object + "?acl&x-id=GetObjectAcl"),
            "GET", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofByteArray()));
        status(200, client.send(signedUri(objectAcl, "PUT",
            acl.body().getBytes(StandardCharsets.UTF_8), Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));

        URI bucketAcl = URI.create(base + "/objects?acl");
        status(200, client.send(signedUri(bucketAcl, "PUT", new byte[0],
            Map.of("x-amz-acl", "public-read")), HttpResponse.BodyHandlers.ofByteArray()));
        status(200, client.send(HttpRequest.newBuilder(
            URI.create(base + "/objects?list-type=2")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(403, client.send(HttpRequest.newBuilder(
            URI.create(base + "/objects?uploads")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(200, client.send(signedUri(bucketAcl, "PUT", new byte[0],
            Map.of("x-amz-grant-write", "id=\"" + SECONDARY + "\"")),
            HttpResponse.BodyHandlers.ofByteArray()));
        URI uploaded = URI.create(base + "/objects/secondary-upload");
        status(200, client.send(signedUriAs(uploaded, "PUT", body, Map.of(),
            SECONDARY, SECONDARY_SECRET), HttpResponse.BodyHandlers.ofByteArray()));
        status(403, client.send(signedUriAs(uploaded, "GET", new byte[0], Map.of(),
            SECONDARY, SECONDARY_SECRET), HttpResponse.BodyHandlers.ofByteArray()));
        status(200, client.send(signedUri(uploaded, "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(200, client.send(signedUri(object, "PUT", body,
            Map.of("x-amz-acl", "public-read")), HttpResponse.BodyHandlers.ofByteArray()));
        status(200, client.send(HttpRequest.newBuilder(object).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(403, client.send(HttpRequest.newBuilder(object).DELETE().build(),
            HttpResponse.BodyHandlers.ofByteArray()));

        URI historyBucket = URI.create(base + "/acl-history");
        status(200, client.send(signedUri(historyBucket, "PUT", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        byte[] versioning = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>"
            .getBytes(StandardCharsets.UTF_8);
        status(200, client.send(signedUri(URI.create(historyBucket + "?versioning"),
            "PUT", versioning, Map.of()), HttpResponse.BodyHandlers.ofByteArray()));
        URI versioned = URI.create(historyBucket + "/versioned");
        var first = client.send(signedUri(versioned, "PUT", body, Map.of("x-amz-acl", "public-read")),
            HttpResponse.BodyHandlers.ofByteArray());
        status(200, first);
        String oldVersion = first.headers().firstValue("x-amz-version-id").orElseThrow();
        status(200, client.send(signedUri(versioned, "PUT", body, Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(403, client.send(HttpRequest.newBuilder(versioned).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()));
        URI historical = URI.create(versioned + "?versionId=" + oldVersion);
        status(200, client.send(HttpRequest.newBuilder(historical).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()));
        URI historicalAcl = URI.create(historical + "&acl");
        status(200, client.send(signedUri(historicalAcl, "PUT", new byte[0],
            Map.of("x-amz-grant-write-acp", "id=\"" + SECONDARY + "\"")),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(200, client.send(signedUriAs(historicalAcl, "PUT", new byte[0],
            Map.of("x-amz-acl", "public-read"), SECONDARY, SECONDARY_SECRET),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(200, client.send(HttpRequest.newBuilder(historical).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(200, client.send(signedUri(historicalAcl, "PUT", new byte[0],
            Map.of("x-amz-acl", "private")), HttpResponse.BodyHandlers.ofByteArray()));
        status(403, client.send(HttpRequest.newBuilder(historical).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static void testStreaming(HttpClient client, String base) throws Exception {
        URI object = URI.create(base + "/objects/streaming-test");
        byte[] body = "verified streaming payload".getBytes(StandardCharsets.UTF_8);
        String date = DATE.format(Instant.now());
        String scope = date.substring(0, 8) + "/" + REGION + "/s3/aws4_request";
        String mode = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD";
        String names = "content-encoding;host;x-amz-content-sha256;x-amz-date;x-amz-decoded-content-length";
        String canonical = "PUT\n" + object.getRawPath() + "\n\ncontent-encoding:aws-chunked\n" +
            "host:" + object.getAuthority() + "\nx-amz-content-sha256:" + mode +
            "\nx-amz-date:" + date + "\nx-amz-decoded-content-length:" + body.length +
            "\n\n" + names + "\n" + mode;
        byte[] signingKey = SigV4.signingKey(SECRET, date.substring(0, 8), REGION);
        String seed = SigV4.hex(SigV4.hmac(signingKey, "AWS4-HMAC-SHA256\n" + date + "\n" +
            scope + "\n" + SigV4.hex(SigV4.hash(canonical.getBytes(StandardCharsets.UTF_8)))));
        String previous = seed;
        var encoded = new java.io.ByteArrayOutputStream();
        for (byte[] chunk : new byte[][]{body, new byte[0]}) {
            String toSign = "AWS4-HMAC-SHA256-PAYLOAD\n" + date + "\n" + scope + "\n" +
                previous + "\n" + SigV4.hex(SigV4.hash(new byte[0])) + "\n" +
                SigV4.hex(SigV4.hash(chunk));
            previous = SigV4.hex(SigV4.hmac(signingKey, toSign));
            encoded.write((Integer.toHexString(chunk.length) + ";chunk-signature=" + previous + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            encoded.write(chunk);
            encoded.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(object)
            .header("content-encoding", "aws-chunked")
            .header("x-amz-content-sha256", mode)
            .header("x-amz-date", date)
            .header("x-amz-decoded-content-length", Integer.toString(body.length))
            .header("authorization", "AWS4-HMAC-SHA256 Credential=" + ACCESS + "/" + scope +
                ",SignedHeaders=" + names + ",Signature=" + seed);
        status(200, client.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(encoded.toByteArray()))
            .build(), HttpResponse.BodyHandlers.ofByteArray()));
        var read = client.send(signedUri(object, "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray());
        status(200, read);
        if (!java.util.Arrays.equals(body, read.body())) throw new AssertionError("Streaming upload mismatch");
        status(204, client.send(signedUri(object, "DELETE", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static void testStreamingTrailer(HttpClient client, String base) throws Exception {
        URI object = URI.create(base + "/objects/streaming-trailer-test");
        byte[] body = "verified trailer payload".getBytes(StandardCharsets.UTF_8);
        String date = DATE.format(Instant.now());
        String scope = date.substring(0, 8) + "/" + REGION + "/s3/aws4_request";
        String mode = "STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER";
        String trailerName = "x-amz-checksum-xxhash3";
        String checksum = encodedChecksum("XXHASH3", body);
        String names = "content-encoding;host;x-amz-content-sha256;x-amz-date;" +
            "x-amz-decoded-content-length;x-amz-sdk-checksum-algorithm;x-amz-trailer";
        String canonical = "PUT\n" + object.getRawPath() + "\n\ncontent-encoding:aws-chunked\n" +
            "host:" + object.getAuthority() + "\nx-amz-content-sha256:" + mode +
            "\nx-amz-date:" + date + "\nx-amz-decoded-content-length:" + body.length +
            "\nx-amz-sdk-checksum-algorithm:XXHASH3\nx-amz-trailer:" + trailerName +
            "\n\n" + names + "\n" + mode;
        byte[] key = SigV4.signingKey(SECRET, date.substring(0, 8), REGION);
        String seed = SigV4.hex(SigV4.hmac(key, "AWS4-HMAC-SHA256\n" + date + "\n" +
            scope + "\n" + SigV4.hex(SigV4.hash(canonical.getBytes(StandardCharsets.UTF_8)))));
        String previous = seed;
        var encoded = new java.io.ByteArrayOutputStream();
        for (byte[] chunk : new byte[][]{body, new byte[0]}) {
            String toSign = "AWS4-HMAC-SHA256-PAYLOAD\n" + date + "\n" + scope + "\n" +
                previous + "\n" + SigV4.hex(SigV4.hash(new byte[0])) + "\n" +
                SigV4.hex(SigV4.hash(chunk));
            previous = SigV4.hex(SigV4.hmac(key, toSign));
            encoded.write((Integer.toHexString(chunk.length) + ";chunk-signature=" + previous + "\r\n")
                .getBytes(StandardCharsets.US_ASCII));
            encoded.write(chunk);
            if (chunk.length != 0) encoded.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        encoded.write((trailerName + ":" + checksum + "\r\n").getBytes(StandardCharsets.US_ASCII));
        String trailerToSign = "AWS4-HMAC-SHA256-TRAILER\n" + date + "\n" + scope + "\n" +
            previous + "\n" + SigV4.hex(SigV4.hash((trailerName + ":" + checksum + "\n")
                .getBytes(StandardCharsets.UTF_8)));
        encoded.write(("x-amz-trailer-signature=" + SigV4.hex(SigV4.hmac(key, trailerToSign)) +
            "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        byte[] upload = encoded.toByteArray();
        HttpRequest.Builder request = HttpRequest.newBuilder(object)
            .header("content-encoding", "aws-chunked")
            .header("x-amz-content-sha256", mode)
            .header("x-amz-date", date)
            .header("x-amz-decoded-content-length", Integer.toString(body.length))
            .header("x-amz-sdk-checksum-algorithm", "XXHASH3")
            .header("x-amz-trailer", trailerName)
            .header("authorization", "AWS4-HMAC-SHA256 Credential=" + ACCESS + "/" + scope +
                ",SignedHeaders=" + names + ",Signature=" + seed);
        status(200, client.send(request.PUT(HttpRequest.BodyPublishers.ofByteArray(upload)).build(),
            HttpResponse.BodyHandlers.ofByteArray()));
        var head = client.send(signedUri(object, "HEAD", new byte[0],
            Map.of("x-amz-checksum-mode", "ENABLED")), HttpResponse.BodyHandlers.discarding());
        status(200, head);
        if (!checksum.equals(head.headers().firstValue(trailerName).orElse("")))
            throw new AssertionError("Signed checksum trailer was not persisted");
        byte[] tampered = upload.clone();
        tampered[upload.length - 10] ^= 1;
        status(400, client.send(HttpRequest.newBuilder(object)
            .header("content-encoding", "aws-chunked")
            .header("x-amz-content-sha256", mode)
            .header("x-amz-date", date)
            .header("x-amz-decoded-content-length", Integer.toString(body.length))
            .header("x-amz-sdk-checksum-algorithm", "XXHASH3")
            .header("x-amz-trailer", trailerName)
            .header("authorization", "AWS4-HMAC-SHA256 Credential=" + ACCESS + "/" + scope +
                ",SignedHeaders=" + names + ",Signature=" + seed)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(tampered)).build(),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(204, client.send(signedUri(object, "DELETE", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static void testCapabilities(HttpClient client, String base) throws Exception {
        URI uri = URI.create(base + "/_objectstore/capabilities");
        status(403, client.send(HttpRequest.newBuilder(uri).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray()));
        var response = client.send(signedUri(uri, "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofString());
        status(200, response);
        String compact = response.body().replaceAll("\\s+", "");
        if (!compact.contains("\"schemaVersion\":1") ||
            !compact.contains("\"service\":\"lunarsky-objectstore\"") ||
            !compact.contains("\"serviceVersion\":\"" + Version.VALUE + "\"") ||
            !compact.contains("\"storageMode\":\"disk\"") ||
            !compact.contains("\"ListParts\"") ||
            !compact.contains("\"maxObjectBytes\":1024") ||
            !compact.contains("\"maxTotalBytes\":4096") ||
            !compact.contains("\"maxParts\":10000"))
            throw new AssertionError("Unexpected capability manifest: " + response.body());
        if (!response.headers().firstValue("content-type").orElse("").startsWith("application/json") ||
            !"no-store".equals(response.headers().firstValue("cache-control").orElse("")))
            throw new AssertionError("Capability response headers missing");
        status(400, client.send(signedUri(URI.create(uri + "?extra=1"), "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static void status(int expected, HttpResponse<?> response) {
        if (response.statusCode() != expected) {
            throw new AssertionError("Expected HTTP " + expected + ", got " + response.statusCode()
                + ": " + (response.body() instanceof byte[] bytes
                    ? new String(bytes, StandardCharsets.UTF_8) : response.body()));
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
        status(404, client.send(signedUri(URI.create(base + "/objects/" + target), "PUT",
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
        if (algorithm.startsWith("XXHASH")) {
            var checksum = new XxHashes(algorithm);
            checksum.update(body, 0, body.length);
            return Base64.getEncoder().encodeToString(checksum.digest());
        }
        if (algorithm.startsWith("CRC")) {
            Checksum checksum = switch (algorithm) {
                case "CRC32" -> new CRC32();
                case "CRC32C" -> new CRC32C();
                case "CRC64NVME" -> new Crc64Nvme();
                default -> throw new IllegalArgumentException(algorithm);
            };
            checksum.update(body, 0, body.length);
            long value = checksum.getValue();
            byte[] bytes = new byte[algorithm.equals("CRC64NVME") ? 8 : 4];
            for (int i = bytes.length - 1; i >= 0; i--) {
                bytes[i] = (byte) value;
                value >>>= 8;
            }
            return Base64.getEncoder().encodeToString(bytes);
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
        for (String algorithm : new String[]{"CRC32", "CRC32C", "CRC64NVME", "XXHASH64",
            "XXHASH3", "XXHASH128", "SHA1", "SHA256", "SHA512", "MD5"}) {
            String header = "x-amz-checksum-" + algorithm.toLowerCase(java.util.Locale.ROOT);
            String checksum = encodedChecksum(algorithm, body);
            var stored = client.send(signedUri(uri, "PUT", body,
                Map.of("content-md5", md5, header, checksum, "x-amz-sdk-checksum-algorithm", algorithm)),
                HttpResponse.BodyHandlers.ofByteArray());
            status(200, stored);
            if (!checksum.equals(stored.headers().firstValue(header).orElse("")))
                throw new AssertionError("Missing checksum response: " + algorithm);
            var bad = client.send(signedUri(uri, "PUT", body,
                Map.of(header, Base64.getEncoder().encodeToString(new byte[algorithm.equals("XXHASH128") ? 16 :
                    algorithm.startsWith("XXHASH") || algorithm.equals("CRC64NVME") ? 8 :
                    algorithm.startsWith("CRC") ? 4 :
                    algorithm.equals("SHA1") ? 20 : algorithm.equals("SHA256") ? 32 :
                    algorithm.equals("SHA512") ? 64 : 16]))), HttpResponse.BodyHandlers.ofString());
            if (bad.statusCode() != 400 || !bad.body().contains("BadDigest"))
                throw new AssertionError("Mismatched " + algorithm + " accepted: " + bad.body());
            var unchanged = client.send(signedUri(uri, "GET", new byte[0], Map.of()),
                HttpResponse.BodyHandlers.ofByteArray());
            status(200, unchanged);
            if (!java.util.Arrays.equals(body, unchanged.body()))
                throw new AssertionError("Bad checksum replaced stored object");
            var head = client.send(signedUri(uri, "HEAD", new byte[0],
                Map.of("x-amz-checksum-mode", "ENABLED")), HttpResponse.BodyHandlers.discarding());
            status(200, head);
            if (!checksum.equals(head.headers().firstValue(header).orElse("")))
                throw new AssertionError("Checksum was not persisted: " + algorithm);
            status(400, client.send(signedUri(uri, "HEAD", new byte[0],
                Map.of("x-amz-checksum-mode", "INVALID")), HttpResponse.BodyHandlers.discarding()));
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
        status(200, client.send(signedUri(uri, "PUT", body, Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        var automatic = client.send(signedUri(uri, "HEAD", new byte[0],
            Map.of("x-amz-checksum-mode", "ENABLED")), HttpResponse.BodyHandlers.discarding());
        status(200, automatic);
        if (!encodedChecksum("CRC64NVME", body).equals(automatic.headers()
            .firstValue("x-amz-checksum-crc64nvme").orElse("")))
            throw new AssertionError("Default CRC64NVME checksum was not persisted");
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

    private static void testAttributes(HttpClient client, String base) throws Exception {
        URI object = URI.create(base + "/objects/attributes.txt");
        byte[] body = "object attributes".getBytes(StandardCharsets.UTF_8);
        status(200, client.send(signedUri(object, "PUT", body, Map.of(
            "x-amz-meta-project", "LunarSky", "x-amz-tagging", "kind=test&phase=one")),
            HttpResponse.BodyHandlers.ofByteArray()));
        var get = client.send(signedUri(object, "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray());
        status(200, get);
        if (!"LunarSky".equals(get.headers().firstValue("x-amz-meta-project").orElse("")) ||
            !"2".equals(get.headers().firstValue("x-amz-tagging-count").orElse("")))
            throw new AssertionError("Stored attributes missing from GET");
        var tagging = URI.create(object + "?tagging");
        var originalTags = client.send(signedUri(URI.create(tagging + "&x-id=GetObjectTagging"),
            "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofString());
        status(200, originalTags);
        if (!originalTags.body().contains("<Key>kind</Key><Value>test</Value>"))
            throw new AssertionError("Object tags were not stored");
        byte[] replacement = "<Tagging><TagSet><Tag><Key>stage</Key><Value>two</Value></Tag></TagSet></Tagging>"
            .getBytes(StandardCharsets.UTF_8);
        status(200, client.send(signedUri(tagging, "PUT", replacement, Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(400, client.send(signedUri(tagging, "PUT", replacement,
            Map.of("content-md5", Base64.getEncoder().encodeToString(new byte[16]))),
            HttpResponse.BodyHandlers.ofByteArray()));
        var replaced = client.send(signedUri(tagging, "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofString());
        if (!replaced.body().contains("<Key>stage</Key><Value>two</Value>") ||
            replaced.body().contains("<Key>kind</Key>")) throw new AssertionError("Tag replacement failed");
        status(400, client.send(signedUri(tagging, "PUT", "<!DOCTYPE x [<!ENTITY y SYSTEM 'file:///etc/passwd'>]><Tagging/>"
            .getBytes(StandardCharsets.UTF_8), Map.of()), HttpResponse.BodyHandlers.ofByteArray()));
        status(204, client.send(signedUri(tagging, "DELETE", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        var cleared = client.send(signedUri(tagging, "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofString());
        if (!cleared.body().contains("<TagSet></TagSet>")) throw new AssertionError("Tag deletion failed");
        status(200, client.send(signedUri(URI.create(base + "/objects/attributes-copy.txt"), "PUT",
            new byte[0], Map.of("x-amz-copy-source", "/objects/attributes.txt")),
            HttpResponse.BodyHandlers.ofByteArray()));
        var copied = client.send(signed(base, "GET", "attributes-copy.txt", new byte[0]),
            HttpResponse.BodyHandlers.ofByteArray());
        if (!"LunarSky".equals(copied.headers().firstValue("x-amz-meta-project").orElse("")))
            throw new AssertionError("Copy lost user metadata");
        status(400, client.send(signedUri(object, "PUT", body,
            Map.of("x-amz-meta-bad", "a".repeat(2100))), HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static void testBuckets(HttpClient client, String base) throws Exception {
        var root = URI.create(base + "/");
        var existing = client.send(signedUri(root, "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofString());
        status(200, existing);
        if (!existing.body().contains("<Name>objects</Name>"))
            throw new AssertionError("Default bucket absent from service listing");
        var bucket = URI.create(base + "/second-bucket");
        status(200, client.send(signedUri(URI.create(bucket + "?x-id=CreateBucket"),
            "PUT", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(409, client.send(signedUri(bucket, "PUT", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(200, client.send(signedUri(bucket, "HEAD", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        var upload = client.send(signedUri(URI.create(base + "/second-bucket/staged.txt?uploads"),
            "POST", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString());
        status(200, upload);
        String uploadId = upload.body().split("<UploadId>")[1].split("</UploadId>")[0];
        status(409, client.send(signedUri(bucket, "DELETE", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(204, client.send(signedUri(URI.create(base + "/second-bucket/staged.txt?uploadId=" + uploadId),
            "DELETE", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofByteArray()));
        byte[] body = "second bucket".getBytes(StandardCharsets.UTF_8);
        var object = URI.create(base + "/second-bucket/one.txt");
        status(200, client.send(signedUri(object, "PUT", body, Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(409, client.send(signedUri(bucket, "DELETE", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        var read = client.send(signedUri(object, "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray());
        status(200, read);
        if (!java.util.Arrays.equals(body, read.body())) throw new AssertionError("Second bucket read failed");
        var crossCopy = URI.create(base + "/second-bucket/copied.txt");
        status(200, client.send(signedUri(crossCopy, "PUT", new byte[0],
            Map.of("x-amz-copy-source", "/objects/attributes.txt")),
            HttpResponse.BodyHandlers.ofByteArray()));
        var copied = client.send(signedUri(crossCopy, "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray());
        if (!"LunarSky".equals(copied.headers().firstValue("x-amz-meta-project").orElse("")))
            throw new AssertionError("Cross-bucket copy lost metadata");
        var listed = client.send(signedUri(URI.create(base + "/second-bucket?list-type=2"),
            "GET", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString());
        if (!listed.body().contains("<Key>one.txt</Key>")) throw new AssertionError("Second bucket listing failed");
        status(204, client.send(signedUri(object, "DELETE", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(204, client.send(signedUri(crossCopy, "DELETE", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(204, client.send(signedUri(bucket, "DELETE", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        status(404, client.send(signedUri(bucket, "HEAD", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
    }

    private static void testVersioning(HttpClient client, String base) throws Exception {
        URI bucket = URI.create(base + "/version-bucket");
        URI configuration = URI.create(bucket + "?versioning");
        URI object = URI.create(bucket + "/note.txt");
        status(200, client.send(signedUri(bucket, "PUT", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        var initial = client.send(signedUri(configuration, "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofString());
        if (!initial.body().contains("<VersioningConfiguration") || initial.body().contains("<Status>"))
            throw new AssertionError("New bucket versioning state");
        status(200, client.send(signedUri(object, "PUT", new byte[]{1}, Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        byte[] enable = "<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>"
            .getBytes(StandardCharsets.UTF_8);
        status(200, client.send(signedUri(configuration, "PUT", enable, Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        var first = client.send(signedUri(object, "PUT", new byte[]{2}, Map.of()),
            HttpResponse.BodyHandlers.ofByteArray());
        status(200, first);
        String id = first.headers().firstValue("x-amz-version-id").orElseThrow();
        var second = client.send(signedUri(object, "PUT", new byte[]{3}, Map.of()),
            HttpResponse.BodyHandlers.ofByteArray());
        status(200, second);
        if (id.equals(second.headers().firstValue("x-amz-version-id").orElseThrow()))
            throw new AssertionError("Version IDs repeated");
        var old = client.send(signedUri(URI.create(object + "?versionId=" + id), "GET",
            new byte[0], Map.of()), HttpResponse.BodyHandlers.ofByteArray());
        status(200, old);
        if (old.body()[0] != 2) throw new AssertionError("Historical object body");
        byte[] tagged = "<Tagging><TagSet><Tag><Key>kind</Key><Value>old</Value></Tag></TagSet></Tagging>"
            .getBytes(StandardCharsets.UTF_8);
        status(200, client.send(signedUri(URI.create(object + "?tagging&versionId=" + id), "PUT",
            tagged, Map.of()), HttpResponse.BodyHandlers.ofByteArray()));
        var oldTags = client.send(signedUri(URI.create(object + "?tagging&versionId=" + id), "GET",
            new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString());
        status(200, oldTags);
        if (!oldTags.body().contains("<Value>old</Value>"))
            throw new AssertionError("Versioned tagging failed");
        var copied = client.send(signedUri(URI.create(bucket + "/copied.txt"), "PUT",
            new byte[0], Map.of("x-amz-copy-source", "/version-bucket/note.txt?versionId=" + id)),
            HttpResponse.BodyHandlers.ofByteArray());
        status(200, copied);
        if (!id.equals(copied.headers().firstValue("x-amz-copy-source-version-id").orElse("")))
            throw new AssertionError("Copy did not report source version");
        var copyBody = client.send(signedUri(URI.create(bucket + "/copied.txt"), "GET",
            new byte[0], Map.of()), HttpResponse.BodyHandlers.ofByteArray());
        status(200, copyBody);
        if (copyBody.body()[0] != 2) throw new AssertionError("Copy of old version failed");
        var deleted = client.send(signedUri(object, "DELETE", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray());
        status(204, deleted);
        String marker = deleted.headers().firstValue("x-amz-version-id").orElseThrow();
        var hidden = client.send(signedUri(object, "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray());
        status(404, hidden);
        if (!"true".equals(hidden.headers().firstValue("x-amz-delete-marker").orElse("")))
            throw new AssertionError("Missing delete marker response header");
        status(405, client.send(signedUri(URI.create(object + "?versionId=" + marker), "GET",
            new byte[0], Map.of()), HttpResponse.BodyHandlers.ofByteArray()));
        var listed = client.send(signedUri(URI.create(bucket + "?versions&max-keys=2"), "GET",
            new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString());
        status(200, listed);
        if (!listed.body().contains("<DeleteMarker>") || !listed.body().contains("<IsTruncated>true</IsTruncated>"))
            throw new AssertionError("Version listing did not include delete marker");
        status(204, client.send(signedUri(URI.create(object + "?versionId=" + marker), "DELETE",
            new byte[0], Map.of()), HttpResponse.BodyHandlers.ofByteArray()));
        var restored = client.send(signedUri(object, "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray());
        status(200, restored);
        if (restored.body()[0] != 3) throw new AssertionError("Deleting marker did not restore current version");
        byte[] suspend = "<VersioningConfiguration><Status>Suspended</Status></VersioningConfiguration>"
            .getBytes(StandardCharsets.UTF_8);
        status(200, client.send(signedUri(configuration, "PUT", suspend, Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        var nullVersion = client.send(signedUri(object, "PUT", new byte[]{4}, Map.of()),
            HttpResponse.BodyHandlers.ofByteArray());
        status(200, nullVersion);
        if (!"null".equals(nullVersion.headers().firstValue("x-amz-version-id").orElse("")))
            throw new AssertionError("Suspended write did not produce null version");
        status(200, client.send(signedUri(configuration, "PUT", enable, Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        URI multipartObject = URI.create(bucket + "/multipart.txt");
        var initiated = client.send(signedUri(URI.create(multipartObject + "?uploads"), "POST",
            new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString());
        status(200, initiated);
        String uploadId = initiated.body().split("<UploadId>")[1].split("</UploadId>")[0];
        byte[] partBody = "versioned multipart".getBytes(StandardCharsets.UTF_8);
        var part = client.send(signedUri(URI.create(multipartObject + "?partNumber=1&uploadId=" + uploadId),
            "PUT", partBody, Map.of()), HttpResponse.BodyHandlers.ofByteArray());
        status(200, part);
        String completion = "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>" +
            part.headers().firstValue("etag").orElseThrow() + "</ETag></Part></CompleteMultipartUpload>";
        var completed = client.send(signedUri(URI.create(multipartObject + "?uploadId=" + uploadId),
            "POST", completion.getBytes(StandardCharsets.UTF_8), Map.of()),
            HttpResponse.BodyHandlers.ofByteArray());
        status(200, completed);
        String multipartVersion = completed.headers().firstValue("x-amz-version-id").orElseThrow();
        status(204, client.send(signedUri(multipartObject, "DELETE", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofByteArray()));
        var retainedMultipart = client.send(signedUri(URI.create(multipartObject + "?versionId=" +
            multipartVersion), "GET", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofByteArray());
        status(200, retainedMultipart);
        if (!java.util.Arrays.equals(partBody, retainedMultipart.body()))
            throw new AssertionError("Completed multipart version was not retained");
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("store-http-test-");
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        DiskStore store = new DiskStore(root, 1024, 4096);
        try {
            var app = new Main(store,
                new SigV4(Map.of(ACCESS, SECRET, SECONDARY, SECONDARY_SECRET),
                    ACCESS, REGION, Clock.systemUTC()), "objects");
            server.setExecutor(executor);
            server.createContext("/", app::handle);
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            testCapabilities(client, base);
            testPresigned(client, base);
            testStreaming(client, base);
            testStreamingTrailer(client, base);
            testObjects(client, base);
            testListing(client, base);
            testCopy(client, base);
            testChecksums(client, base);
            testMultipart(client, base);
            testAttributes(client, base);
            testDelete(client, base);
            testBuckets(client, base);
            testVersioning(client, base);
            testAcl(client, base);
            System.out.println("HTTP tests passed: capabilities, objects, copy, checksums, listing, multipart, attributes, buckets, versioning, ACLs");
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
