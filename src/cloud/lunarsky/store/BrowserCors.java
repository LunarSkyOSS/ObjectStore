package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BrowserCors {
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "PUT", "POST", "DELETE");
    private static final Set<String> HEADERS = Set.of("authorization", "content-type", "content-md5",
        "range", "if-match", "if-none-match");
    private final Set<String> origins;

    private BrowserCors(Set<String> origins) { this.origins = origins; }

    static BrowserCors disabled() { return new BrowserCors(Set.of()); }

    static BrowserCors fromEnvironment(Map<String, String> env) {
        String value = env.getOrDefault("CORS_ALLOWED_ORIGINS", "").trim();
        if (value.isEmpty()) return disabled();
        Set<String> allowed = new HashSet<>();
        for (String item : value.split(",", -1)) {
            String origin = item.trim();
            URI uri;
            try { uri = URI.create(origin); }
            catch (IllegalArgumentException error) { throw new IllegalArgumentException("Invalid CORS origin", error); }
            if (origin.length() > 255 || uri.getScheme() == null ||
                !Set.of("http", "https").contains(uri.getScheme()) ||
                uri.getHost() == null || uri.getRawUserInfo() != null ||
                uri.getRawQuery() != null || uri.getRawFragment() != null ||
                uri.getRawPath() != null && !uri.getRawPath().isEmpty() ||
                !origin.equals(uri.getScheme() + "://" + uri.getRawAuthority()))
                throw new IllegalArgumentException("CORS origins must be exact http(s) origins");
            allowed.add(origin);
        }
        if (allowed.size() > 32) throw new IllegalArgumentException("Too many CORS origins");
        return new BrowserCors(Set.copyOf(allowed));
    }

    void decorate(HttpExchange exchange) {
        if (origins.isEmpty()) return;
        exchange.getResponseHeaders().set("Vary", "Origin");
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || !origins.contains(origin)) return;
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        exchange.getResponseHeaders().set("Access-Control-Expose-Headers",
            "ETag, Content-Length, Content-Range, Last-Modified, x-amz-request-id, " +
            "x-amz-version-id, x-amz-delete-marker, x-amz-checksum-crc64nvme, x-amz-checksum-sha256");
    }

    boolean preflight(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("OPTIONS") || origins.isEmpty()) return false;
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || !origins.contains(origin))
            throw new StoreException(403, "AccessDenied", "CORS origin is not allowed");
        String method = exchange.getRequestHeaders().getFirst("Access-Control-Request-Method");
        if (method == null || !METHODS.contains(method))
            throw new StoreException(403, "AccessDenied", "CORS method is not allowed");
        String requested = exchange.getRequestHeaders().getFirst("Access-Control-Request-Headers");
        if (requested != null && !requested.isBlank()) {
            String[] headers = requested.split(",", -1);
            if (headers.length > 32 || Arrays.stream(headers).anyMatch(header -> !allowedHeader(header.trim())))
                throw new StoreException(403, "AccessDenied", "CORS header is not allowed");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers",
                String.join(", ", Arrays.stream(headers).map(String::trim).toList()));
        }
        exchange.getResponseHeaders().set("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", String.join(", ", METHODS));
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "600");
        exchange.sendResponseHeaders(204, -1);
        return true;
    }

    private static boolean allowedHeader(String header) {
        String name = header.toLowerCase(Locale.ROOT);
        return name.matches("[a-z0-9-]{1,80}") &&
            (HEADERS.contains(name) || name.startsWith("x-amz-"));
    }
}
