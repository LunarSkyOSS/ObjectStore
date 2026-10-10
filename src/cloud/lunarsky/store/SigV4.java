package cloud.lunarsky.store;

import com.sun.net.httpserver.Headers;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class SigV4 {
    record Verified(String payload, String applicationQuery, byte[] signingKey,
                    String date, String scope, String signature, String principal) {
        Verified(String payload, String applicationQuery, byte[] signingKey,
                 String date, String scope, String signature) {
            this(payload, applicationQuery, signingKey, date, scope, signature, null);
        }
        boolean streaming() { return payload.startsWith("STREAMING-AWS4-HMAC-SHA256-PAYLOAD"); }
    }
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");
    private final Map<String, String> identities;
    private final String root;
    private final String region;
    private final Clock clock;

    SigV4(String accessKey, String secretKey, String region, Clock clock) {
        this(Map.of(accessKey, secretKey), accessKey, region, clock);
    }

    SigV4(Map<String, String> identities, String root, String region, Clock clock) {
        this.identities = Map.copyOf(identities);
        if (!this.identities.containsKey(root)) throw new IllegalArgumentException("Missing root identity");
        this.root = root;
        this.region = region;
        this.clock = clock;
    }

    String root() { return root; }
    Set<String> identities() { return identities.keySet(); }

    String verify(String method, URI uri, Headers headers) {
        return verifyRequest(method, uri, headers).payload();
    }

    Verified verifyRequest(String method, URI uri, Headers headers) {
        if (hasPresignedQuery(uri.getRawQuery())) return verifyPresigned(method, uri, headers);
        Map<String, String> fields = authorizationFields(headers);
        String[] credential = credentialScope(fields.get("Credential"));
        String date = signingDate(headers, credential[1]);
        String payload = payloadHash(headers);
        String signedHeaders = fields.get("SignedHeaders");
        String canonicalHeaders = canonicalHeaders(headers, signedHeaders,
            Set.of("host", "x-amz-date", "x-amz-content-sha256"));
        String canonical = method + "\n" + encode(decode(uri.getRawPath()), true) + "\n"
            + canonicalQuery(uri.getRawQuery()) + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payload;
        String scope = String.join("/", Arrays.copyOfRange(credential, 1, 5));
        String toSign = "AWS4-HMAC-SHA256\n" + date + "\n" + scope + "\n" + hex(hash(canonical.getBytes(StandardCharsets.UTF_8)));
        byte[] signingKey = signingKey(secret(credential[0]), credential[1], region);
        String signature = fields.get("Signature");
        if (!HEX.matcher(signature).matches() || !MessageDigest.isEqual(hmac(signingKey, toSign), HexFormat.of().parseHex(signature))) denied("Signature mismatch");
        return new Verified(payload, uri.getRawQuery(), signingKey, date, scope, signature, credential[0]);
    }

    private Verified verifyPresigned(String method, URI uri, Headers headers) {
        if (headers.containsKey("authorization")) denied("Use one authentication method");
        Map<String, String> fields = new TreeMap<>();
        StringBuilder application = new StringBuilder();
        StringBuilder signed = new StringBuilder();
        for (String part : uri.getRawQuery().split("&", -1)) {
            String[] pair = part.split("=", 2);
            String name = decode(pair[0]);
            String value = decode(pair.length == 2 ? pair[1] : "");
            if (name.startsWith("X-Amz-")) {
                if (fields.put(name, value) != null) denied("Duplicate presigned parameter");
                if (!name.equals("X-Amz-Signature")) appendQuery(signed, part);
            } else {
                appendQuery(application, part);
                appendQuery(signed, part);
            }
        }
        if (!fields.keySet().equals(Set.of("X-Amz-Algorithm", "X-Amz-Credential", "X-Amz-Date",
                "X-Amz-Expires", "X-Amz-SignedHeaders", "X-Amz-Signature")) ||
            !"AWS4-HMAC-SHA256".equals(fields.get("X-Amz-Algorithm")))
            denied("Invalid presigned parameters");
        String[] credential = credentialScope(fields.get("X-Amz-Credential"));
        String date = fields.get("X-Amz-Date");
        if (!date.matches("[0-9]{8}T[0-9]{6}Z") || !date.startsWith(credential[1]))
            denied("Invalid signing date");
        long expires;
        try { expires = Long.parseLong(fields.get("X-Amz-Expires")); }
        catch (NumberFormatException error) { denied("Invalid presigned expiry"); return null; }
        if (expires < 1 || expires > 604800) denied("Invalid presigned expiry");
        try {
            Instant start = Instant.from(DATE.parse(date));
            Instant now = clock.instant();
            if (now.isBefore(start.minus(Duration.ofMinutes(5))) || now.isAfter(start.plusSeconds(expires)))
                denied("Presigned URL has expired or is not yet valid");
        } catch (java.time.DateTimeException error) { denied("Invalid signing date"); }
        String signedHeaders = fields.get("X-Amz-SignedHeaders");
        String canonicalHeaders = canonicalHeaders(headers, signedHeaders, Set.of("host"));
        String scope = String.join("/", Arrays.copyOfRange(credential, 1, 5));
        String canonical = method + "\n" + encode(decode(uri.getRawPath()), true) + "\n"
            + canonicalQuery(signed.toString()) + "\n" + canonicalHeaders + "\n"
            + signedHeaders + "\nUNSIGNED-PAYLOAD";
        String toSign = "AWS4-HMAC-SHA256\n" + date + "\n" + scope + "\n"
            + hex(hash(canonical.getBytes(StandardCharsets.UTF_8)));
        String signature = fields.get("X-Amz-Signature");
        byte[] key = signingKey(secret(credential[0]), credential[1], region);
        if (!HEX.matcher(signature).matches() ||
            !MessageDigest.isEqual(hmac(key, toSign), HexFormat.of().parseHex(signature)))
            denied("Signature mismatch");
        return new Verified("UNSIGNED-PAYLOAD", application.toString(), key, date, scope, signature, credential[0]);
    }

    private static boolean hasPresignedQuery(String raw) {
        return raw != null && (raw.startsWith("X-Amz-Algorithm=") || raw.contains("&X-Amz-Algorithm="));
    }

    private static void appendQuery(StringBuilder target, String part) {
        if (!target.isEmpty()) target.append('&');
        target.append(part);
    }

    private static Map<String, String> authorizationFields(Headers headers) {
        String authorization = single(headers, "authorization");
        if (authorization == null || !authorization.startsWith("AWS4-HMAC-SHA256 ")) denied("Signed requests are required");
        Map<String, String> fields = new TreeMap<>();
        for (String part : authorization.substring(17).split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2 || fields.put(pair[0], pair[1]) != null) denied("Invalid authorization header");
        }
        if (!fields.keySet().equals(java.util.Set.of("Credential", "SignedHeaders", "Signature"))) denied("Invalid authorization fields");
        return fields;
    }

    private String[] credentialScope(String value) {
        String[] credential = value.split("/", -1);
        if (credential.length != 5 || !identities.containsKey(credential[0]) || !credential[2].equals(region)
            || !credential[3].equals("s3") || !credential[4].equals("aws4_request")) denied("Invalid credential scope");
        return credential;
    }

    private String secret(String accessKey) {
        String secret = identities.get(accessKey);
        if (secret == null) denied("Invalid credential scope");
        return secret;
    }

    private String signingDate(Headers headers, String credentialDate) {
        String date = single(headers, "x-amz-date");
        if (date == null || !credentialDate.matches("[0-9]{8}") || !date.matches("[0-9]{8}T[0-9]{6}Z") || !date.startsWith(credentialDate)) denied("Invalid signing date");
        try {
            Instant signed = Instant.from(DATE.parse(date));
            if (Duration.between(signed, clock.instant()).abs().compareTo(Duration.ofMinutes(5)) > 0)
                throw new StoreException(403, "RequestTimeTooSkewed", "Request timestamp is outside the permitted window");
        } catch (java.time.DateTimeException e) { denied("Invalid signing date"); }
        return date;
    }

    private static String payloadHash(Headers headers) {
        String payload = single(headers, "x-amz-content-sha256");
        if (payload == null || !(HEX.matcher(payload).matches() ||
            payload.equals("STREAMING-AWS4-HMAC-SHA256-PAYLOAD") ||
            payload.equals("STREAMING-AWS4-HMAC-SHA256-PAYLOAD-TRAILER")))
            throw new StoreException(400, "NotImplemented", "Unsupported SHA-256 payload mode");
        if (headers.containsKey("x-amz-security-token")) denied("Temporary credentials are unsupported");
        return payload;
    }

    private static String canonicalHeaders(Headers headers, String signedHeaders, Set<String> required) {
        String[] names = signedHeaders.split(";", -1);
        if (names.length > 32 || !signedHeaders.equals(String.join(";", Arrays.stream(names).distinct().sorted().toList()))) denied("Signed headers must be unique and sorted");
        var namesSet = java.util.Set.copyOf(Arrays.asList(names));
        if (!namesSet.containsAll(required)) denied("Missing signed headers");
        for (String key : headers.keySet()) {
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("x-amz-") && !namesSet.contains(lower)) denied("Unsigned Amazon header");
        }
        if (headers.containsKey("if-none-match") && !namesSet.contains("if-none-match")) denied("Unsigned write condition");
        StringBuilder canonicalHeaders = new StringBuilder();
        for (String name : names) {
            if (!name.matches("[a-z0-9-]+")) denied("Invalid signed header name");
            String value = single(headers, name);
            if (value == null) denied("Missing signed header");
            canonicalHeaders.append(name).append(':').append(value.trim().replaceAll("[\\t ]+", " ")).append('\n');
        }
        return canonicalHeaders.toString();
    }

    static String single(Headers headers, String name) {
        var values = headers.get(name);
        if (values == null) return null;
        if (values.size() != 1) denied("Duplicate security-relevant header");
        return values.getFirst();
    }

    static String decode(String value) {
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            for (int i = 0; i < value.length();) {
                if (value.charAt(i) == '%') {
                    if (i + 2 >= value.length()) throw new IllegalArgumentException();
                    int hi = Character.digit(value.charAt(i + 1), 16);
                    int lo = Character.digit(value.charAt(i + 2), 16);
                    if (hi < 0 || lo < 0) throw new IllegalArgumentException();
                    bytes.write((hi << 4) | lo);
                    i += 3;
                } else {
                    int point = value.codePointAt(i);
                    bytes.writeBytes(new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8));
                    i += Character.charCount(point);
                }
            }
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException e) {
            throw new StoreException(400, "InvalidURI", "Malformed URI encoding");
        }
    }

    static String encode(String value, boolean keepSlash) {
        StringBuilder result = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 255;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~' || (c == '/' && keepSlash)) result.append((char)c);
            else result.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 15));
        }
        return result.toString();
    }

    static String canonicalQuery(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return Arrays.stream(raw.split("&", -1)).map(part -> {
            String[] pair = part.split("=", 2);
            return encode(decode(pair[0]), false) + "=" + encode(decode(pair.length == 2 ? pair[1] : ""), false);
        }).sorted().collect(java.util.stream.Collectors.joining("&"));
    }

    static byte[] signingKey(String secret, String date, String region) {
        return hmac(hmac(hmac(hmac(("AWS4"+secret).getBytes(StandardCharsets.UTF_8),date),region),"s3"),"aws4_request");
    }
    static byte[] hmac(byte[] key, String text) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
        }
        catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static byte[] hash(byte[] data) {
        try { return MessageDigest.getInstance("SHA-256").digest(data); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static String hex(byte[] data) { return HexFormat.of().formatHex(data); }
    private static void denied(String message) { throw new StoreException(403,"AccessDenied",message); }
}
