package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

final class S3Request {
    private S3Request() { }

    static void validateObjectHeaders(com.sun.net.httpserver.Headers headers) {
        for (String name : headers.keySet()) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("x-amz-") && !lower.startsWith("x-amz-meta-") && !java.util.Set.of("x-amz-date", "x-amz-content-sha256",
                "x-amz-security-token",
                "x-amz-sdk-checksum-algorithm", "x-amz-user-agent", "x-amz-copy-source",
                "x-amz-metadata-directive", "x-amz-tagging", "x-amz-tagging-directive",
                "x-amz-decoded-content-length", "x-amz-trailer", "x-amz-checksum-mode",
                "x-amz-acl").contains(lower) && !lower.startsWith("x-amz-checksum-") &&
                !lower.startsWith("x-amz-grant-"))
                unsupported("Amazon header");
            if (lower.startsWith("x-amz-server-side-") ||
                lower.startsWith("x-amz-copy-source-")) unsupported("Object metadata, encryption, ACL or tagging header");
        }
    }
    static void verifyContentMd5(com.sun.net.httpserver.Headers headers, byte[] body) {
        String encoded = SigV4.single(headers, "content-md5");
        if (encoded == null) return;
        byte[] expected;
        try { expected = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException error) {
            throw new StoreException(400, "InvalidDigest", "Invalid Content-MD5");
        }
        if (expected.length != 16) throw new StoreException(400, "InvalidDigest", "Invalid Content-MD5");
        try {
            byte[] actual = MessageDigest.getInstance("MD5").digest(body);
            if (!MessageDigest.isEqual(expected, actual))
                throw new StoreException(400, "BadDigest", "Content-MD5 mismatch");
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static UploadChecksums uploadChecksums(Headers headers, Map<String, String> query) {
        if (query.keySet().stream().noneMatch(name -> name.startsWith("x-amz-checksum-") ||
            name.equals("x-amz-sdk-checksum-algorithm"))) return UploadChecksums.from(headers);
        Headers combined = new Headers();
        headers.forEach((name, values) -> combined.put(name, java.util.List.copyOf(values)));
        query.forEach((name, value) -> {
            if (!name.startsWith("x-amz-checksum-") && !name.equals("x-amz-sdk-checksum-algorithm")) return;
            if (combined.containsKey(name))
                throw new StoreException(400, "InvalidRequest", "Duplicate checksum in query and header");
            combined.set(name, value);
        });
        return UploadChecksums.from(combined);
    }
    static String contentType(com.sun.net.httpserver.Headers headers) {
        String value = SigV4.single(headers, "content-type");
        if (value == null) return "application/octet-stream";
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 255 ||
            !value.chars().allMatch(c -> c >= 32 && c <= 126))
            throw new StoreException(400, "InvalidArgument", "Invalid Content-Type");
        return value;
    }

    static void requireEmptyBody(HttpExchange exchange, String hash) {
        var headers = exchange.getRequestHeaders();
        if (headers.containsKey("transfer-encoding") ||
            (headers.containsKey("content-length") && !"0".equals(SigV4.single(headers, "content-length"))) ||
            !(hash.equals("UNSIGNED-PAYLOAD") || hash.equals(SigV4.hex(SigV4.hash(new byte[0])))))
            throw new StoreException(400, "InvalidRequest", "Request must have an empty body");
    }

    static boolean multipartRequest(String method, Map<String, String> query) {
        if (query.containsKey("uploads"))
            return method.equals("POST") && query.get("uploads").isEmpty() &&
                query.keySet().stream().allMatch(java.util.Set.of("uploads", "x-id")::contains) &&
                (!query.containsKey("x-id") || query.get("x-id").equals("CreateMultipartUpload"));
        if (!query.containsKey("uploadId") ||
            !query.keySet().stream().allMatch(java.util.Set.of("uploadId", "partNumber",
                "part-number-marker", "max-parts", "x-id")::contains))
            return false;
        String xId = query.get("x-id");
        if (method.equals("PUT")) return query.containsKey("partNumber") &&
            !query.containsKey("part-number-marker") && !query.containsKey("max-parts") &&
            (xId == null || xId.equals("UploadPart"));
        if (query.containsKey("partNumber")) return false;
        if (method.equals("GET")) return xId == null || xId.equals("ListParts");
        if (query.containsKey("part-number-marker") || query.containsKey("max-parts")) return false;
        return (method.equals("POST") && (xId == null || xId.equals("CompleteMultipartUpload"))) ||
            (method.equals("DELETE") && (xId == null || xId.equals("AbortMultipartUpload")));
    }
    static int boundedNumber(String text, int minimum, int maximum, int defaultValue) {
        if (text == null) return defaultValue;
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException error) {
            throw new StoreException(400, "InvalidArgument", "Invalid listing limit or marker");
        }
        if (value < minimum || value > maximum)
            throw new StoreException(400, "InvalidArgument", "Invalid listing limit or marker");
        return value;
    }

    static long decodedLength(com.sun.net.httpserver.Headers headers) {
        String value = SigV4.single(headers, "x-amz-decoded-content-length");
        if (value == null || !value.matches("[0-9]{1,19}"))
            throw new StoreException(400, "InvalidArgument", "Invalid decoded content length");
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) {
            throw new StoreException(400, "InvalidArgument", "Invalid decoded content length");
        }
    }

    static AwsChunkedInputStream chunkedBody(HttpExchange exchange,
                                                      SigV4.Verified verified) {
        var headers = exchange.getRequestHeaders();
        if (!"aws-chunked".equals(SigV4.single(headers, "content-encoding")))
            throw new StoreException(400, "InvalidRequest", "Signed chunk upload requires aws-chunked encoding");
        if (contentLength(headers) < 0 && !headers.containsKey("transfer-encoding"))
            throw new StoreException(411, "MissingContentLength", "Encoded content length is required");
        long length = decodedLength(headers);
        String trailer = SigV4.single(headers, "x-amz-trailer");
        if (verified.payload().endsWith("-TRAILER")) {
            if (trailer == null || !trailer.matches("x-amz-checksum-[a-z0-9]+"))
                throw new StoreException(400, "InvalidRequest", "A checksum trailer is required");
        } else if (trailer != null) {
            throw new StoreException(400, "InvalidRequest", "Unexpected checksum trailer");
        }
        return new AwsChunkedInputStream(exchange.getRequestBody(), verified, length, trailer);
    }

    static long contentLength(com.sun.net.httpserver.Headers headers) {
        String text = SigV4.single(headers, "content-length");
        if (text == null) return -1;
        try { return Long.parseLong(text); }
        catch (NumberFormatException e) { throw new StoreException(400, "InvalidArgument", "Invalid Content-Length"); }
    }
    static String expectedPayloadHash(String hash) {
        return hash.matches("[0-9a-f]{64}") ? hash : null;
    }
    static byte[] signedBody(HttpExchange exchange, String hash, int limit) throws IOException {
        if (!hash.equals("UNSIGNED-PAYLOAD") && !hash.matches("[0-9a-f]{64}"))
            throw new StoreException(400, "InvalidRequest", "Unsupported body signing mode");
        long length = contentLength(exchange.getRequestHeaders());
        if (length < 0) throw new StoreException(411, "MissingContentLength", "Content-Length is required");
        if (length > limit) throw new StoreException(413, "EntityTooLarge", "Request body is too large");
        byte[] body = exchange.getRequestBody().readNBytes(limit + 1);
        if (body.length != length) throw new StoreException(400, "IncompleteBody", "Body length does not match Content-Length");
        if (!hash.equals("UNSIGNED-PAYLOAD") && !SigV4.hex(SigV4.hash(body)).equals(hash))
            throw new StoreException(400, "XAmzContentSHA256Mismatch", "Payload hash mismatch");
        return body;
    }
    record Range(long start, long end, boolean partial) {
        long length() { return end < start ? 0 : end - start + 1; }
    }
    static Range range(String header, long size) {
        if (header == null) return new Range(0, size - 1, false);
        if (!header.matches("bytes=[0-9]*-[0-9]*") || header.equals("bytes=-") || size == 0)
            throw new StoreException(416, "InvalidRange", "The requested range is not satisfiable");
        String[] parts = header.substring(6).split("-", -1);
        try {
            long start, end;
            if (parts[0].isEmpty()) {
                long suffix = Long.parseLong(parts[1]);
                if (suffix == 0) throw new NumberFormatException();
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(parts[0]);
                end = parts[1].isEmpty() ? size - 1 : Math.min(Long.parseLong(parts[1]), size - 1);
            }
            if (start >= size || end < start) throw new NumberFormatException();
            return new Range(start, end, true);
        } catch (NumberFormatException e) {
            throw new StoreException(416, "InvalidRange", "The requested range is not satisfiable");
        }
    }
    record ListRequest(String prefix, String delimiter, String encoding, int maxKeys, String after) { }

    static ListRequest listRequest(Map<String, String> query) {
        String prefix = query.getOrDefault("prefix", ""), delimiter = query.getOrDefault("delimiter", "");
        String encoding = query.get("encoding-type");
        if (encoding != null && !encoding.equals("url")) unsupported("Encoding type");
        if (query.containsKey("continuation-token") && query.containsKey("start-after"))
            throw new StoreException(400, "InvalidArgument", "Use either continuation-token or start-after");
        int maxKeys;
        try { maxKeys = Integer.parseInt(query.getOrDefault("max-keys", "1000")); }
        catch (NumberFormatException e) { throw new StoreException(400, "InvalidArgument", "Invalid max-keys"); }
        if (maxKeys < 0 || maxKeys > 1000) throw new StoreException(400, "InvalidArgument", "Invalid max-keys");
        String after = query.get("start-after");
        if (query.containsKey("continuation-token")) {
            try {
                byte[] decoded = Base64.getUrlDecoder().decode(query.get("continuation-token"));
                after = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(decoded)).toString();
            } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException e) {
                throw new StoreException(400, "InvalidArgument", "Invalid continuation token");
            }
        }
        return new ListRequest(prefix, delimiter, encoding, maxKeys, after);
    }
    static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String part : raw.split("&", -1)) {
            String[] pair = part.split("=", 2);
            String name = SigV4.decode(pair[0]);
            String value = SigV4.decode(pair.length == 2 ? pair[1] : "");
            if (result.put(name, value) != null)
                throw new StoreException(400, "InvalidArgument", "Duplicate query parameter");
        }
        return result;
    }
    static void unsupported(String feature) { throw new StoreException(501, "NotImplemented", feature + " is not supported"); }
}
