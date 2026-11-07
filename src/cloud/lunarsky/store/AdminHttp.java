package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class AdminHttp {
    private static final int MAX_FORM_BYTES = 128 * 1024;

    private AdminHttp() { }

    static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim()
            .equalsIgnoreCase("application/x-www-form-urlencoded")) {
            throw new StoreException(415, "InvalidRequest", "Expected a form submission");
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_FORM_BYTES + 1);
        if (body.length > MAX_FORM_BYTES) {
            throw new StoreException(413, "InvalidRequest", "Form is too large");
        }
        Map<String, String> values = new java.util.HashMap<>();
        try {
            for (String field : new String(body, StandardCharsets.UTF_8).split("&", -1)) {
                int split = field.indexOf('=');
                if (split < 0) continue;
                String key = URLDecoder.decode(field.substring(0, split), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(field.substring(split + 1), StandardCharsets.UTF_8);
                if (values.putIfAbsent(key, value) != null)
                    throw new IllegalArgumentException("Duplicate form field");
            }
        } catch (IllegalArgumentException error) {
            throw new StoreException(400, "InvalidRequest", "Invalid form submission");
        }
        return values;
    }

    static void securityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Security-Policy",
            "default-src 'none'; style-src 'self'; style-src-elem 'self' 'unsafe-inline'; " +
            "style-src-attr 'none'; script-src 'self'; img-src 'self' data:; connect-src 'self'; form-action 'self'; " +
            "frame-ancestors 'none'; base-uri 'none'");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    }

    static void methodNotAllowed(HttpExchange exchange, String methods) throws IOException {
        exchange.getResponseHeaders().set("Allow", methods);
        send(exchange, 405, "Method not allowed", "text/plain; charset=utf-8");
    }

    static void redirect(HttpExchange exchange, String path) throws IOException {
        exchange.getResponseHeaders().set("Location", path);
        exchange.sendResponseHeaders(303, -1);
    }

    static void send(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
