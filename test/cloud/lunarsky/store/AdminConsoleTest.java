package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.Executors;

public final class AdminConsoleTest {
    private static final String PASSWORD = "a-very-long-test-admin-password";

    private static void status(int wanted, HttpResponse<?> response) {
        if (response.statusCode() != wanted)
            throw new AssertionError("Expected " + wanted + ", got " + response.statusCode() + ": " + response.body());
    }

    private static HttpRequest.Builder request(String base, String path) {
        return HttpRequest.newBuilder(URI.create(base + AdminConsole.PATH + path));
    }

    private static HttpResponse<String> post(HttpClient client, String base, String path,
                                             String password, String csrf, String origin, String cookie) throws Exception {
        var builder = request(base, path).header("Content-Type", "application/x-www-form-urlencoded");
        if (origin != null) builder.header("Origin", origin);
        if (cookie != null) builder.header("Cookie", cookie);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(
            "password=" + java.net.URLEncoder.encode(password, StandardCharsets.UTF_8) +
            "&csrf=" + java.net.URLEncoder.encode(csrf, StandardCharsets.UTF_8))).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static String csrf(String page) {
        var match = java.util.regex.Pattern.compile("name=csrf value='([A-Za-z0-9_-]{43})'").matcher(page);
        if (!match.find()) throw new AssertionError("CSRF token missing from page");
        return match.group(1);
    }

    private static String loginToken(HttpClient client, String base) throws Exception {
        return csrf(client.send(request(base, "/login").GET().build(),
            HttpResponse.BodyHandlers.ofString()).body());
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("admin-console-test-");
        Path passwordFile = root.resolve("password");
        Files.writeString(passwordFile, PASSWORD + "\n");
        DiskStore store = new DiskStore(root.resolve("data"), 1024, 4096);
        store.ensureBucket("photos");
        byte[] image = "small preview".getBytes(StandardCharsets.UTF_8);
        store.put("photos", "images/cat.txt", new ByteArrayInputStream(image), image.length,
            SigV4.hex(SigV4.hash(image)), null, true, "text/plain");
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        try {
            if (AdminConsole.fromEnvironment(Map.of(), store) != null)
                throw new AssertionError("Admin console enabled without configuration");
            try {
                AdminConsole.fromEnvironment(Map.of("ADMIN_UI_ENABLED", "true"), store);
                throw new AssertionError("Admin console enabled without password file");
            } catch (IllegalArgumentException expected) { }
            try {
                AdminConsole.fromEnvironment(Map.of("ADMIN_UI_ENABLED", "true",
                    "ADMIN_UI_PASSWORD_FILE", passwordFile.toString(),
                    "ADMIN_UI_ORIGIN", "http://public.example"), store);
                throw new AssertionError("Admin console accepted a remote HTTP origin");
            } catch (IllegalArgumentException expected) { }
            AdminConsole console = AdminConsole.fromEnvironment(Map.of(
                "ADMIN_UI_ENABLED", "true", "ADMIN_UI_PASSWORD_FILE", passwordFile.toString()), store);
            var app = new Main(store, new MultipartStore(store),
                new SigV4("TESTACCESSKEY123", "test-secret-key-that-is-at-least-32-characters",
                    "us-east-1", Clock.systemUTC()), "objects");
            app.setAdminConsole(console);
            server.setExecutor(executor);
            server.createContext("/", app::handle);
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
                socket.getOutputStream().write(("GET " + AdminConsole.PATH + "/login HTTP/1.1\r\n" +
                    "Host: public.example\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!response.startsWith("HTTP/1.1 403"))
                    throw new AssertionError("Public Host header reached the admin console");
            }
            status(303, client.send(request(base, "/overview").GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            for (String path : new String[] { "/performance", "/performance.json", "/performance.js",
                "/buckets", "/bucket?name=photos",
                "/object?bucket=photos&key=images%2Fcat.txt" }) {
                status(303, client.send(request(base, path).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            }
            var loginPage = client.send(request(base, "/login").GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, loginPage);
            if (!loginPage.body().contains("Admin password") ||
                !loginPage.body().contains("/_objectstore/admin/logo.png") ||
                !loginPage.headers().firstValue("Cache-Control").orElse("").equals("no-store"))
                throw new AssertionError("Login page or cache control missing");
            String policy = loginPage.headers().firstValue("Content-Security-Policy").orElse("");
            if (!policy.contains("style-src 'self'") || !policy.contains("img-src 'self'"))
                throw new AssertionError("Admin console content policy missing");
            var logo = client.send(request(base, "/logo.png").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            status(200, logo);
            if (!logo.headers().firstValue("Content-Type").orElse("").equals("image/png") ||
                logo.body().length < 8 || logo.body()[0] != (byte) 0x89 ||
                logo.body()[1] != 'P' || logo.body()[2] != 'N' || logo.body()[3] != 'G')
                throw new AssertionError("ObjectStore icon is unavailable");
            var style = client.send(request(base, "/style.css").GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, style);
            if (!style.body().contains("color-scheme: dark") ||
                !style.body().contains("--biscuit-chart-surface: #211c2b"))
                throw new AssertionError("Dark chart styles missing");
            status(303, client.send(request(base, "/biscuit.js").GET().build(),
                HttpResponse.BodyHandlers.discarding()));
            status(403, post(client, base, "/login", PASSWORD, "invalid", null, null));
            status(401, post(client, base, "/login", "incorrect", loginToken(client, base), null, null));
            var login = post(client, base, "/login", PASSWORD, loginToken(client, base), null, null);
            status(303, login);
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            if (!login.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly; SameSite=Strict"))
                throw new AssertionError("Session cookie protections missing");
            var overview = client.send(request(base, "/overview").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, overview);
            if (!overview.body().contains("Storage mode") ||
                !overview.body().contains("biscuit-cardview") || !overview.body().contains("photos") ||
                !overview.body().contains("/performance") ||
                !overview.body().contains("/_objectstore/admin/logo.png"))
                throw new AssertionError("Authenticated overview missing");
            var performance = client.send(request(base, "/performance").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, performance);
            if (!performance.headers().firstValue("Content-Security-Policy").orElse("")
                .contains("connect-src 'self'"))
                throw new AssertionError("Performance polling is blocked by content policy");
            if (!performance.body().contains("perf-transfer-chart") ||
                !performance.body().contains("Process RAM") ||
                !performance.body().contains("performance.js"))
                throw new AssertionError("Performance view missing");
            if (performance.body().split("theme=dark type=line", -1).length != 5)
                throw new AssertionError("Performance charts must use Biscuit's dark renderer");
            status(200, client.send(request(base, "/performance.js").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            for (int poll = 0; poll < 2; poll++) {
                var sample = client.send(request(base, "/performance.json")
                    .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
                status(200, sample);
                if (!sample.body().contains("\"requests\":0") ||
                    !sample.body().contains("\"gcCount\":") ||
                    !sample.body().contains("\"heapBytes\":"))
                    throw new AssertionError("Admin polling was counted as S3 traffic or system data is missing");
            }
            var bucketIndex = client.send(request(base, "/buckets").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, bucketIndex);
            if (!bucketIndex.body().contains("/bucket?name=photos"))
                throw new AssertionError("Bucket index did not link to photos");
            var bucket = client.send(request(base, "/bucket?name=photos").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, bucket);
            if (!bucket.body().contains("images/") || !bucket.body().contains("Bucket settings"))
                throw new AssertionError("Bucket folder or settings missing");
            var folder = client.send(request(base, "/bucket?name=photos&prefix=images%2F")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            status(200, folder);
            if (!folder.body().contains("cat.txt") || !folder.body().contains("biscuit-chartview") ||
                !folder.body().contains("theme=dark") ||
                !folder.body().contains("this page only"))
                throw new AssertionError("Folder listing or bounded chart missing");
            var object = client.send(request(base, "/object?bucket=photos&key=images%2Fcat.txt")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            status(200, object);
            if (!object.body().contains("Content type") || !object.body().contains("text/plain") ||
                !object.body().contains("SHA-256"))
                throw new AssertionError("Object metadata missing");
            status(400, client.send(request(base, "/bucket?name=photos&name=other")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString()));
            var reflected = client.send(request(base, "/buckets?filter=%3Cscript%3E")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            status(200, reflected);
            if (reflected.body().contains("value='<script>'") ||
                !reflected.body().contains("&lt;script&gt;"))
                throw new AssertionError("Bucket search was not escaped");
            status(200, client.send(request(base, "/biscuit.js").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.discarding()));
            status(403, post(client, base, "/logout", "", "invalid", null, cookie));
            status(200, client.send(request(base, "/overview").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            status(303, post(client, base, "/logout", "", csrf(overview.body()), null, cookie));
            status(303, client.send(request(base, "/overview").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            for (int i = 0; i < 5; i++) {
                status(401, post(client, base, "/login", "incorrect", loginToken(client, base), null, null));
            }
            status(429, post(client, base, "/login", PASSWORD, loginToken(client, base), null, null));
            System.out.println("Admin console tests passed");
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
