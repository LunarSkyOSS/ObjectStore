package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static cloud.lunarsky.store.AdminConsole.PATH;
import static cloud.lunarsky.store.AdminHttp.*;

final class AdminAssets {
    private record Asset(byte[] content, String type, boolean authenticated) { }

    private final Map<String, Asset> assets;

    AdminAssets() throws IOException {
        Map<String, Asset> loaded = new HashMap<>();
        add(loaded, "/style.css", "admin.css", "text/css", false);
        add(loaded, "/logo.png", "objectstore-logo.png", "image/png", false);
        add(loaded, "/workspace.js", "workspace.js", "text/javascript", false);
        add(loaded, "/biscuit.js", "biscuit.js", "text/javascript", true);
        add(loaded, "/performance.js", "performance.js", "text/javascript", true);
        for (String icon : AdminLayout.ICONS) {
            add(loaded, "/icons/" + icon + ".svg", "icons/" + icon + ".svg", "image/svg+xml", true);
        }
        assets = Map.copyOf(loaded);
    }

    boolean handle(HttpExchange exchange, boolean signedIn) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        Asset asset = assets.get(path);
        boolean icon = path.startsWith(PATH + "/icons/") && path.endsWith(".svg");
        if (asset == null && !icon) return false;
        if (!exchange.getRequestMethod().equals("GET")) {
            methodNotAllowed(exchange, "GET");
        } else if ((icon || asset.authenticated()) && !signedIn) {
            redirect(exchange, PATH + "/login");
        } else if (asset == null) {
            send(exchange, 404, "Not found", "text/plain; charset=utf-8");
        } else {
            exchange.getResponseHeaders().set("Content-Type", asset.type());
            exchange.sendResponseHeaders(200, asset.content().length);
            exchange.getResponseBody().write(asset.content());
        }
        return true;
    }

    private static void add(Map<String, Asset> assets, String route, String file, String type,
                            boolean authenticated) throws IOException {
        String contentType = type.equals("image/png") ? type : type + "; charset=" + StandardCharsets.UTF_8;
        assets.put(PATH + route, new Asset(Files.readAllBytes(Path.of("admin-ui", file)),
            contentType, authenticated));
    }
}
