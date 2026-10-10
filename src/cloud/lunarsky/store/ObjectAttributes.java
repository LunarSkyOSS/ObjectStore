package cloud.lunarsky.store;

import com.sun.net.httpserver.Headers;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

final class ObjectAttributes {
    private ObjectAttributes() {}

    static Map<String, String> userMetadata(Headers headers) {
        Map<String, String> values = new TreeMap<>();
        int total = 0;
        for (String name : headers.keySet()) {
            if (!name.toLowerCase(java.util.Locale.ROOT).startsWith("x-amz-meta-")) continue;
            String key = name.substring(11).toLowerCase(java.util.Locale.ROOT);
            if (!key.matches("[a-z0-9][a-z0-9._-]{0,127}"))
                throw new StoreException(400, "InvalidArgument", "Invalid user metadata key");
            String value = SigV4.single(headers, name);
            if (value == null || !value.chars().allMatch(c -> c >= 32 && c <= 126))
                throw new StoreException(400, "InvalidArgument", "Invalid user metadata value");
            total += key.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length;
            values.put(key, value);
        }
        if (total > 2048) throw new StoreException(400, "MetadataTooLarge", "User metadata exceeds 2 KiB");
        return Map.copyOf(values);
    }

    static Map<String, String> tagsHeader(String raw) {
        if (raw == null) return Map.of();
        Map<String, String> tags = new TreeMap<>();
        if (raw.isEmpty()) return tags;
        for (String pair : raw.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2) throw new StoreException(400, "InvalidTag", "Invalid tagging header");
            String key = SigV4.decode(parts[0]);
            String value = SigV4.decode(parts[1]);
            if (tags.put(key, value) != null) throw new StoreException(400, "InvalidTag", "Duplicate tag key");
        }
        validateTags(tags);
        return Map.copyOf(tags);
    }

    static void validateTags(Map<String, String> tags) {
        if (tags.size() > 10) throw new StoreException(400, "InvalidTag", "Too many object tags");
        for (var entry : tags.entrySet()) {
            if (entry.getKey().isEmpty() || entry.getKey().length() > 128 ||
                entry.getValue().length() > 256 ||
                !xmlText(entry.getKey()) || !xmlText(entry.getValue()))
                throw new StoreException(400, "InvalidTag", "Invalid tag key or value");
        }
    }

    private static boolean xmlText(String value) {
        for (int i = 0; i < value.length();) {
            int point = value.codePointAt(i);
            if (point < 32 || point > 0x10ffff || point >= 0xd800 && point <= 0xdfff ||
                point >= 0xfffe && point <= 0xffff) return false;
            i += Character.charCount(point);
        }
        return true;
    }

    static byte[] encode(Map<String, String> values, int limit) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeShort(values.size());
            for (var entry : new TreeMap<>(values).entrySet()) {
                byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8);
                byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
                output.writeShort(key.length);
                output.writeShort(value.length);
                output.write(key);
                output.write(value);
            }
            if (bytes.size() > limit) throw new StoreException(400, "InvalidArgument", "Object attributes are too large");
            return bytes.toByteArray();
        } catch (IOException error) { throw new IllegalStateException(error); }
    }

    static Map<String, String> decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) return Map.of();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int count = input.readUnsignedShort();
            if (count > 128) throw new IOException("Invalid object attributes");
            Map<String, String> values = new TreeMap<>();
            for (int i = 0; i < count; i++) {
                int keyLength = input.readUnsignedShort(), valueLength = input.readUnsignedShort();
                String key = decodeUtf8(input.readNBytes(keyLength), keyLength);
                String value = decodeUtf8(input.readNBytes(valueLength), valueLength);
                if (values.put(key, value) != null) throw new IOException("Duplicate object attribute");
            }
            if (input.available() != 0) throw new IOException("Trailing object attributes");
            return Map.copyOf(values);
        }
    }

    private static String decodeUtf8(byte[] bytes, int length) throws IOException {
        if (bytes.length != length) throw new IOException("Truncated object attributes");
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IOException("Invalid object attributes", error);
        }
    }
}
