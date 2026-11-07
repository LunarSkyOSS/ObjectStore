package cloud.lunarsky.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

final class AdminTextEditor {
    static final int MAX_BYTES = 32 * 1024;

    private AdminTextEditor() { }

    static boolean supports(ObjectStorage.Metadata metadata) {
        if (metadata.length() > MAX_BYTES) return false;
        String type = metadata.contentType() == null ? "" :
            metadata.contentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (type.startsWith("text/") || type.equals("application/json") ||
            type.equals("application/xml") || type.equals("application/javascript") ||
            type.equals("application/x-javascript") || type.equals("application/x-yaml")) return true;
        String key = metadata.key().toLowerCase(Locale.ROOT);
        return type.equals("application/octet-stream") &&
            (key.endsWith(".txt") || key.endsWith(".json") || key.endsWith(".xml") ||
                key.endsWith(".md") || key.endsWith(".html") || key.endsWith(".css") ||
                key.endsWith(".js") || key.endsWith(".java") || key.endsWith(".yaml") ||
                key.endsWith(".yml"));
    }

    static String read(ObjectStorage.OpenObject opened) throws IOException {
        byte[] data = opened.stream().readNBytes(MAX_BYTES + 1);
        if (data.length > MAX_BYTES) throw new StoreException(413, "EntityTooLarge", "Text preview is too large");
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data)).toString();
            if (text.indexOf('\0') >= 0)
                throw new StoreException(415, "InvalidObjectState", "This object contains binary data");
            return text;
        } catch (CharacterCodingException error) {
            throw new StoreException(415, "InvalidObjectState", "This object is not UTF-8 text");
        }
    }

    static String language(ObjectStorage.Metadata metadata) {
        String key = metadata.key().toLowerCase(Locale.ROOT);
        String type = metadata.contentType() == null ? "" : metadata.contentType().toLowerCase(Locale.ROOT);
        if (key.endsWith(".json") || type.contains("json")) return "json";
        if (key.endsWith(".html") || type.contains("html")) return "html";
        if (key.endsWith(".css") || type.contains("css")) return "css";
        if (key.endsWith(".js") || type.contains("javascript")) return "javascript";
        if (key.endsWith(".java")) return "java";
        return "plain";
    }

    static String save(ObjectStorage store, Map<String, String> form) throws IOException {
        String bucket = form.getOrDefault("bucket", "");
        String key = form.getOrDefault("key", "");
        if (bucket.isEmpty() || bucket.length() > 63 || key.isEmpty() || key.length() > 1024)
            throw new StoreException(400, "InvalidArgument", "Invalid object path");
        if (store.bucket(bucket).versioning() != ObjectStorage.VersioningState.ENABLED)
            throw new StoreException(409, "InvalidBucketState", "Enable bucket versioning before editing objects");
        String value = form.getOrDefault("content", "");
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_BYTES)
            throw new StoreException(413, "EntityTooLarge", "Text files are limited to 32 KiB in the admin editor");
        ObjectStorage.Metadata metadata;
        try (ObjectStorage.OpenObject opened = store.open(bucket, key)) {
            metadata = opened.metadata();
            if (!supports(metadata))
                throw new StoreException(415, "InvalidObjectState", "Only small text objects can be edited");
            read(opened);
        }
        if (metadata.versionId() == null || !metadata.versionId().equals(form.get("originalVersion")) ||
            metadata.sha256() == null || !SigV4.hex(metadata.sha256()).equals(form.get("originalSha256")))
            throw new StoreException(409, "Conflict", "The object changed. Reload before saving your edit");
        try {
            store.replaceVersion(bucket, key, data, metadata);
        } catch (StoreException error) {
            if (error.status == 412)
                throw new StoreException(409, "Conflict", "The object changed. Reload before saving your edit");
            throw error;
        } finally {
            Arrays.fill(data, (byte) 0);
        }
        return AdminConsole.PATH + "/object?bucket=" + AdminBrowser.encoded(bucket) +
            "&key=" + AdminBrowser.encoded(key);
    }
}
