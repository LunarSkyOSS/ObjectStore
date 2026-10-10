package cloud.lunarsky.objectstore.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class SigV4 {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
        .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
        .withZone(ZoneOffset.UTC);

    private SigV4() { }

    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String encode(String text, boolean keepSlash) {
        StringBuilder result = new StringBuilder();
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            int b = value & 0xff;
            if (b >= 'A' && b <= 'Z' || b >= 'a' && b <= 'z' || b >= '0' && b <= '9' ||
                b == '-' || b == '_' || b == '.' || b == '~' || keepSlash && b == '/') result.append((char) b);
            else result.append('%').append(Character.toUpperCase(Character.forDigit(b >>> 4, 16)))
                .append(Character.toUpperCase(Character.forDigit(b & 15, 16)));
        }
        return result.toString();
    }

    static String query(Map<String, String> values) {
        List<String> entries = new ArrayList<>();
        values.forEach((key, value) -> entries.add(encode(key, false) + "=" + encode(value, false)));
        entries.sort(String::compareTo);
        return String.join("&", entries);
    }

    static String authorization(String method, URI uri, Map<String, String> headers, String payloadHash,
                                Instant now, String region, String accessKey, String secretKey) {
        TreeMap<String, String> signed = new TreeMap<>();
        headers.forEach((name, value) -> signed.put(name.toLowerCase(Locale.ROOT),
            value.trim().replaceAll("\\s+", " ")));
        String names = String.join(";", signed.keySet());
        StringBuilder canonicalHeaders = new StringBuilder();
        signed.forEach((name, value) -> canonicalHeaders.append(name).append(':').append(value).append('\n'));
        String canonical = method + '\n' + uri.getRawPath() + '\n' +
            (uri.getRawQuery() == null ? "" : uri.getRawQuery()) + '\n' + canonicalHeaders + '\n' +
            names + '\n' + payloadHash;
        String day = DATE.format(now);
        String scope = day + '/' + region + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + TIME.format(now) + '\n' + scope + '\n' +
            hash(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] key = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
        key = hmac(key, region);
        key = hmac(key, "s3");
        key = hmac(key, "aws4_request");
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + '/' + scope + ",SignedHeaders=" + names +
            ",Signature=" + HexFormat.of().formatHex(hmac(key, toSign));
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("HMAC-SHA256 unavailable", e); }
    }

    static String timestamp(Instant now) { return TIME.format(now); }
}
