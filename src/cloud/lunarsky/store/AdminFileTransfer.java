package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

final class AdminFileTransfer {
    private AdminFileTransfer() { }

    static String upload(ObjectStorage store, HttpExchange exchange) throws IOException {
        Map<String, String> query = AdminBrowser.query(exchange.getRequestURI().getRawQuery(),
            Set.of("bucket", "key", "overwrite"));
        String bucket = required(query, "bucket", 63);
        String key = required(query, "key", 1024);
        boolean overwrite = "1".equals(query.get("overwrite"));
        if (query.containsKey("overwrite") && !overwrite)
            throw new StoreException(400, "InvalidArgument", "Invalid overwrite option");
        ObjectStorage.Bucket current = store.bucket(bucket);
        if (overwrite && current.versioning() != ObjectStorage.VersioningState.ENABLED)
            throw new StoreException(409, "InvalidBucketState", "Enable versioning before replacing an object");
        String lengthHeader = exchange.getRequestHeaders().getFirst("Content-Length");
        long length;
        try { length = Long.parseLong(lengthHeader); }
        catch (NumberFormatException | NullPointerException error) {
            throw new StoreException(411, "MissingContentLength", "Upload size is required");
        }
        if (length < 0 || length > store.limits().maxObjectBytes())
            throw new StoreException(413, "EntityTooLarge", "File exceeds the configured object limit");
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || type.isBlank()) type = "application/octet-stream";
        if (type.length() > 255 || type.chars().anyMatch(character -> character < 32 || character == 127))
            throw new StoreException(400, "InvalidArgument", "Invalid content type");
        store.put(bucket, key, exchange.getRequestBody(), length, null, null, !overwrite, type);
        return AdminConsole.PATH + "/object?bucket=" + AdminBrowser.encoded(bucket) +
            "&key=" + AdminBrowser.encoded(key);
    }

    static void download(ObjectStorage store, HttpExchange exchange) throws IOException {
        Map<String, String> query = AdminBrowser.query(exchange.getRequestURI().getRawQuery(),
            Set.of("bucket", "key", "version"));
        String bucket = required(query, "bucket", 63);
        String key = required(query, "key", 1024);
        String version = query.get("version");
        if (version != null && (version.isEmpty() || version.length() > 256))
            throw new StoreException(400, "InvalidArgument", "Invalid object version");
        try (ObjectStorage.OpenObject opened = store.open(bucket, key, version)) {
            String filename = key.substring(key.lastIndexOf('/') + 1);
            if (filename.isEmpty()) filename = "object";
            String encoded = java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A");
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().set("Content-Disposition",
                "attachment; filename=\"download\"; filename*=UTF-8''" + encoded);
            exchange.sendResponseHeaders(200, opened.metadata().length());
            opened.stream().transferTo(exchange.getResponseBody());
        }
    }

    private static String required(Map<String, String> query, String name, int maxLength) {
        String value = query.get(name);
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > maxLength)
            throw new StoreException(400, "InvalidArgument", "Invalid object path");
        return value;
    }
}
