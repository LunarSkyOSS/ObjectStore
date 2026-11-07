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

    private static HttpResponse<String> action(HttpClient client, String base, String name,
                                               String cookie, String origin, String form) throws Exception {
        var builder = request(base, "/action/" + name)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", cookie);
        if (origin != null) builder.header("Origin", origin);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(form)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> upload(HttpClient client, String base, String path,
                                               String cookie, String origin, String csrf,
                                               byte[] content) throws Exception {
        var builder = request(base, "/upload?" + path)
            .header("Content-Type", "text/plain")
            .header("Cookie", cookie);
        if (origin != null) builder.header("Origin", origin);
        if (csrf != null) builder.header("X-ObjectStore-CSRF", csrf);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static String loginToken(HttpClient client, String base) throws Exception {
        return csrf(client.send(request(base, "/login").GET().build(),
            HttpResponse.BodyHandlers.ofString()).body());
    }

    private static String rawRequest(int port, String host, String method, String path, String body) throws Exception {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write((method + " " + AdminConsole.PATH + path + " HTTP/1.1\r\n" +
                "Host: " + host + "\r\nConnection: close\r\n" +
                (method.equals("POST") ? "Content-Type: application/x-www-form-urlencoded\r\n" +
                    "Content-Length: " + content.length + "\r\n" : "") +
                "\r\n").getBytes(StandardCharsets.US_ASCII));
            if (content.length > 0) socket.getOutputStream().write(content);
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("admin-console-test-");
        Path legacyBranding = root.resolve("legacy-branding.txt");
        Files.writeString(legacyBranding, "ObjectStoreBranding 1\n" +
            java.util.Base64.getEncoder().encodeToString("Old name".getBytes(StandardCharsets.UTF_8)) +
            "\n\n\n");
        AdminBranding migrated = new AdminBranding(legacyBranding);
        if (!migrated.state().name().equals("Old name") ||
            !migrated.state().defaultTheme().equals("deep-dark"))
            throw new AssertionError("Legacy branding or deep-dark default was lost");
        migrated.setDefaultTheme("light");
        if (!new AdminBranding(legacyBranding).state().defaultTheme().equals("light") ||
            !new AdminBranding(legacyBranding).state().name().equals("Old name"))
            throw new AssertionError("Global theme did not persist across branding migration");
        Path passwordFile = root.resolve("password");
        Files.writeString(passwordFile, PASSWORD + "\n");
        Path masterFile = root.resolve("credential-master");
        Files.writeString(masterFile, "0123456789abcdef".repeat(4));
        DiskStore store = new DiskStore(root.resolve("data"), 1024, 4096);
        store.ensureBucket("photos");
        byte[] image = "small preview".getBytes(StandardCharsets.UTF_8);
        store.put("photos", "images/cat.txt", new ByteArrayInputStream(image), image.length,
            SigV4.hex(SigV4.hash(image)), null, true, "text/plain");
        DiskBucketNames names = new DiskBucketNames(store, new MultipartStore(store));
        BucketActivity activity = new BucketActivity(root.resolve("data/bucket-activity.tsv"));
        activity.record("photos", "GET");
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        HttpServer s3Server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
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
            for (String invalid : new String[] { "http://public.example:9001", "http://8.8.8.8:9001",
                "http://10.30.0.120", "https://10.30.0.120:9001" }) {
                try {
                    AdminConsole.fromEnvironment(Map.of("ADMIN_UI_ENABLED", "true",
                        "ADMIN_UI_PASSWORD_FILE", passwordFile.toString(),
                        "ADMIN_UI_PRIVATE_ORIGIN", invalid), store);
                    throw new AssertionError("Admin console accepted invalid private origin: " + invalid);
                } catch (IllegalArgumentException expected) { }
            }
            AdminConsole console = AdminConsole.fromEnvironment(Map.of(
                "ADMIN_UI_ENABLED", "true", "ADMIN_UI_PASSWORD_FILE", passwordFile.toString(),
                "DATA_DIR", root.resolve("data").toString()), names);
            console.setDiskStore(store);
            var app = new Main(names, names,
                new SigV4("TESTACCESSKEY123", "test-secret-key-that-is-at-least-32-characters",
                    "us-east-1", Clock.systemUTC()), "objects");
            app.setBucketActivity(activity);
            app.setAdminConsole(console);
            ManagedCredentials managed = ManagedCredentials.fromEnvironment(Map.of(
                "S3_MANAGED_KEYS_MASTER_FILE", masterFile.toString(),
                "DATA_DIR", root.resolve("data").toString()), store, java.util.Set.of("TESTACCESSKEY123"));
            app.setManagedCredentials(managed);
            server.setExecutor(executor);
            server.createContext("/", app::handleAdmin);
            server.start();
            s3Server.setExecutor(executor);
            s3Server.createContext("/", app::handle);
            s3Server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            String s3Base = "http://127.0.0.1:" + s3Server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            status(404, client.send(request(s3Base, "/login").GET().build(),
                HttpResponse.BodyHandlers.discarding()));
            status(404, client.send(HttpRequest.newBuilder(URI.create(base + "/health")).GET().build(),
                HttpResponse.BodyHandlers.discarding()));
            try (Socket socket = new Socket("127.0.0.1", server.getAddress().getPort())) {
                socket.getOutputStream().write(("GET " + AdminConsole.PATH + "/login HTTP/1.1\r\n" +
                    "Host: public.example\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (!response.startsWith("HTTP/1.1 403"))
                    throw new AssertionError("Public Host header reached the admin console");
            }
            status(303, client.send(request(base, "/overview").GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            for (String path : new String[] { "/performance", "/performance.json", "/performance.js", "/settings",
                "/health", "/diagnostics", "/diagnostics/probe.bin",
                "/audit", "/jobs", "/cluster",
                "/buckets", "/bucket?name=photos",
                "/object?bucket=photos&key=images%2Fcat.txt" }) {
                status(303, client.send(request(base, path).GET().build(),
                    HttpResponse.BodyHandlers.ofString()));
            }
            status(401, client.send(request(base, "/jobs.json?id=abcdefghijklmnop").GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            var loginPage = client.send(request(base, "/login").GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, loginPage);
            if (!loginPage.body().contains("Admin password") ||
                !loginPage.body().contains("/_objectstore/admin/logo.png") ||
                !loginPage.headers().firstValue("Cache-Control").orElse("").equals("no-store"))
                throw new AssertionError("Login page or cache control missing");
            var loginAppearance = client.send(request(base, "/workspace.js").GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, loginAppearance);
            if (!loginAppearance.body().contains("applyPreferences"))
                throw new AssertionError("Login appearance script missing");
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
                !overview.body().contains("/_objectstore/admin/logo.png") ||
                !overview.body().contains("biscuit-sidebarview") ||
                !overview.body().contains("biscuit-navbarview") ||
                !overview.body().contains("/icons/bucket.svg") ||
                !overview.body().contains("/icons/activity.svg") ||
                !overview.body().contains("class=mobile-nav-link") ||
                !overview.body().contains("biscuit-footerview"))
                throw new AssertionError("Authenticated overview missing");
            var workspace = client.send(request(base, "/workspace.js").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, workspace);
            var bucketIcon = client.send(request(base, "/icons/bucket.svg")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            status(200, bucketIcon);
            if (!bucketIcon.body().contains("<svg"))
                throw new AssertionError("Biscuit bucket icon is unavailable");
            if (!workspace.body().contains("registerCodeLanguage(\"json\""))
                throw new AssertionError("CodeView JSON highlighting is unavailable");
            var healthView = client.send(request(base, "/health").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, healthView);
            if (!healthView.body().contains("Filesystem available") ||
                !healthView.body().contains("Latest restore test") ||
                !healthView.body().contains("Not reported"))
                throw new AssertionError("Health view implies an unverified backup or hides disk state");
            var diagnosticsView = client.send(request(base, "/diagnostics").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, diagnosticsView);
            if (!diagnosticsView.body().contains("p50 / p95") ||
                !diagnosticsView.body().contains("Recent errors") ||
                !diagnosticsView.body().contains("diagnostic-probe"))
                throw new AssertionError("Request diagnostics view missing");
            var auditView = client.send(request(base, "/audit").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, auditView);
            if (!auditView.body().contains("Check access") ||
                !auditView.body().contains("Admin event trail"))
                throw new AssertionError("Audit and access view missing");
            var accessResult = action(client, base, "access-check", cookie, base,
                "csrf=" + csrf(overview.body()) +
                "&principal=TESTACCESSKEY123&operation=list&bucket=photos&key=");
            status(200, accessResult);
            if (!accessResult.body().contains("Owner or ACL grants this action"))
                throw new AssertionError("Access checker did not use live S3 permissions");
            var jobsView = client.send(request(base, "/jobs").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, jobsView);
            if (!jobsView.body().contains("Bulk jobs") || !jobsView.body().contains("Export inventory"))
                throw new AssertionError("Bulk jobs view missing");
            var clusterView = client.send(request(base, "/cluster").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, clusterView);
            if (!clusterView.body().contains("disk mode"))
                throw new AssertionError("Disk gateway should not claim cluster health");
            var probe = client.send(request(base, "/diagnostics/probe.bin").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            status(200, probe);
            if (probe.body().length != 1024 * 1024 ||
                !probe.headers().firstValue("Cache-Control").orElse("").equals("no-store"))
                throw new AssertionError("Connection probe size or cache control changed");
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
            if (performance.body().split("theme=deep-dark type=line", -1).length != 5)
                throw new AssertionError("Performance charts must use Biscuit's dark renderer");
            status(200, client.send(request(base, "/performance.js").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            status(303, client.send(request(base, "/performance.events").GET().build(),
                HttpResponse.BodyHandlers.discarding()));
            var events = client.send(request(base, "/performance.events")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            status(200, events);
            if (!events.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream") ||
                !new String(events.body().readNBytes(6), StandardCharsets.UTF_8).equals("data: "))
                throw new AssertionError("Performance event stream is unavailable");
            events.body().close();
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
            if (!bucketIndex.body().contains("biscuit-formwizardview") ||
                !bucketIndex.body().contains("blockPublicPolicy") ||
                !bucketIndex.body().contains("Public access blocked") ||
                !bucketIndex.body().contains("biscuit-pageloaderview"))
                throw new AssertionError("New bucket setup wizard is missing");
            var bucket = client.send(request(base, "/bucket?name=photos").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, bucket);
            if (!bucket.body().contains("images/") || !bucket.body().contains("Bucket settings") ||
                !bucket.body().contains("biscuit-tablayout") ||
                !bucket.body().contains("biscuit-activity-chart") ||
                !bucket.body().contains("Daily S3 requests"))
                throw new AssertionError("Bucket folder or settings missing");
            if (!bucket.body().contains("Upload file") || !bucket.body().contains("class='settings-form upload-form'"))
                throw new AssertionError("Bucket upload controls missing");
            var folder = client.send(request(base, "/bucket?name=photos&prefix=images%2F")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            status(200, folder);
            if (!folder.body().contains("cat.txt") || !folder.body().contains("biscuit-chartview") ||
                !folder.body().contains("theme=deep-dark") ||
                !folder.body().contains("this page only"))
                throw new AssertionError("Folder listing or bounded chart missing");
            var object = client.send(request(base, "/object?bucket=photos&key=images%2Fcat.txt")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            status(200, object);
            if (!object.body().contains("Content type") || !object.body().contains("text/plain") ||
                !object.body().contains("SHA-256"))
                throw new AssertionError("Object metadata missing");
            var versions = client.send(request(base, "/versions?bucket=photos&prefix=images%2F")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            status(200, versions);
            if (!versions.body().contains("cat.txt") || !versions.body().contains("&amp;version=null"))
                throw new AssertionError("Version browser did not link to the object");
            status(200, client.send(request(base,
                "/object?bucket=photos&key=images%2Fcat.txt&version=null")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString()));
            String adminCsrf = csrf(overview.body());
            byte[] uploadBody = "uploaded from admin".getBytes(StandardCharsets.UTF_8);
            String uploadPath = "bucket=photos&key=images%2Fupload.txt";
            status(401, client.send(request(base, "/upload?" + uploadPath)
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofByteArray(uploadBody)).build(),
                HttpResponse.BodyHandlers.ofString()));
            status(403, upload(client, base, uploadPath, cookie, base, null, uploadBody));
            status(403, upload(client, base, uploadPath, cookie, "https://evil.example",
                adminCsrf, uploadBody));
            var uploaded = upload(client, base, uploadPath, cookie, base, adminCsrf, uploadBody);
            status(201, uploaded);
            if (!uploaded.headers().firstValue("Location").orElse("")
                .equals(AdminConsole.PATH + "/object?bucket=photos&key=images%2Fupload.txt"))
                throw new AssertionError("Upload did not link to the object");
            status(412, upload(client, base, uploadPath, cookie, base, adminCsrf, uploadBody));
            status(409, upload(client, base, uploadPath + "&overwrite=1", cookie, base,
                adminCsrf, uploadBody));
            status(413, upload(client, base, "bucket=photos&key=too-large.txt", cookie, base,
                adminCsrf, new byte[1025]));
            var downloaded = client.send(request(base, "/download?" + uploadPath)
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            status(200, downloaded);
            if (!java.util.Arrays.equals(uploadBody, downloaded.body()) ||
                !downloaded.headers().firstValue("Content-Disposition").orElse("")
                    .contains("filename*=UTF-8''upload.txt"))
                throw new AssertionError("Admin download changed file content or filename");
            status(303, client.send(request(base, "/download?" + uploadPath).GET().build(),
                HttpResponse.BodyHandlers.discarding()));
            status(400, client.send(request(base, "/download?bucket=photos&key=x&key=y")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.discarding()));
            status(403, action(client, base, "create-bucket", cookie, null, "bucket=archive"));
            status(403, action(client, base, "create-bucket", cookie, "https://evil.example",
                "csrf=" + adminCsrf + "&bucket=archive"));
            status(303, action(client, base, "create-bucket", cookie, base,
                "csrf=" + adminCsrf + "&bucket=archive"));
            status(400, action(client, base, "create-bucket", cookie, base,
                "csrf=" + adminCsrf + "&bucket=wizard-bucket&setup=wizard&versioning=enabled" +
                "&ownership=BucketOwnerPreferred&ignorePublicAcls=on&blockPublicPolicy=on" +
                "&restrictPublicBuckets=on"));
            status(303, action(client, base, "create-bucket", cookie, base,
                "csrf=" + adminCsrf + "&bucket=wizard-bucket&setup=wizard&versioning=enabled" +
                "&ownership=BucketOwnerPreferred&ignorePublicAcls=on&blockPublicPolicy=on" +
                "&restrictPublicBuckets=on&acknowledgePublic=yes"));
            var wizardBucket = names.bucket("wizard-bucket");
            if (wizardBucket.versioning() != ObjectStorage.VersioningState.ENABLED ||
                wizardBucket.ownership() != ObjectStorage.ObjectOwnership.BUCKET_OWNER_PREFERRED ||
                wizardBucket.publicAccessBlock().blockPublicAcls())
                throw new AssertionError("Bucket wizard did not apply initial settings");
            var keys = client.send(request(base, "/credentials").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, keys);
            if (!keys.body().contains("Create scoped key") || !keys.body().contains("photos"))
                throw new AssertionError("Access key controls missing");
            status(403, action(client, base, "create-key", cookie, base,
                "bucket=photos&prefix=images%2F&read=on"));
            var newKey = action(client, base, "create-key", cookie, base,
                "csrf=" + adminCsrf + "&bucket=photos&prefix=images%2F&read=on&write=on&list=on");
            status(200, newKey);
            if (!newKey.body().contains("Save this secret now") || managed.list().size() != 1)
                throw new AssertionError("Managed access key was not created");
            ManagedCredentials.Key scoped = managed.list().getFirst();
            status(409, action(client, base, "rename-bucket", cookie, base,
                "csrf=" + adminCsrf + "&bucket=photos&newName=other-photos&confirm=photos"));
            if (!names.bucket("photos").name().equals("photos"))
                throw new AssertionError("Scoped-key rejection changed the bucket name");
            if (!newKey.body().contains(scoped.secret()) ||
                client.send(request(base, "/credentials").header("Cookie", cookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body().contains(scoped.secret()))
                throw new AssertionError("Secret must be shown once");
            if (Files.readString(root.resolve("data/managed-credentials.bin"), StandardCharsets.ISO_8859_1)
                .contains(scoped.secret()))
                throw new AssertionError("Managed secret was stored in cleartext");
            var restored = ManagedCredentials.fromEnvironment(Map.of(
                "S3_MANAGED_KEYS_MASTER_FILE", masterFile.toString(),
                "DATA_DIR", root.resolve("data").toString()), store, java.util.Set.of("TESTACCESSKEY123"));
            if (!restored.list().getFirst().secret().equals(scoped.secret()))
                throw new AssertionError("Managed key was not persisted");
            status(200, client.send(HttpTest.signedUriAs(URI.create(s3Base + "/photos/images/cat.txt"),
                "GET", new byte[0], Map.of(), scoped.id(), scoped.secret()),
                HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(s3Base + "/photos/outside.txt"),
                "GET", new byte[0], Map.of(), scoped.id(), scoped.secret()),
                HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(HttpTest.signedUriAs(URI.create(s3Base + "/photos?list-type=2&prefix=images%2F"),
                "GET", new byte[0], Map.of(), scoped.id(), scoped.secret()),
                HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(HttpTest.signedUriAs(URI.create(s3Base + "/photos"),
                "HEAD", new byte[0], Map.of(), scoped.id(), scoped.secret()),
                HttpResponse.BodyHandlers.discarding()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(s3Base + "/photos?list-type=2"),
                "GET", new byte[0], Map.of(), scoped.id(), scoped.secret()),
                HttpResponse.BodyHandlers.ofString()));
            status(400, action(client, base, "disable-key", cookie, base,
                "csrf=" + adminCsrf + "&id=" + scoped.id()));
            status(303, action(client, base, "disable-key", cookie, base,
                "csrf=" + adminCsrf + "&id=" + scoped.id() + "&confirm=" + scoped.id()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(s3Base + "/photos/images/cat.txt"),
                "GET", new byte[0], Map.of(), scoped.id(), scoped.secret()),
                HttpResponse.BodyHandlers.ofString()));
            status(303, action(client, base, "enable-key", cookie, base,
                "csrf=" + adminCsrf + "&id=" + scoped.id() + "&confirm=" + scoped.id()));
            status(200, client.send(HttpTest.signedUriAs(URI.create(s3Base + "/photos/images/cat.txt"),
                "GET", new byte[0], Map.of(), scoped.id(), scoped.secret()),
                HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(s3Base + "/objects/images/cat.txt"),
                "GET", new byte[0], Map.of(), scoped.id(), scoped.secret()),
                HttpResponse.BodyHandlers.ofString()));
            status(400, action(client, base, "update-key", cookie, base,
                "csrf=" + adminCsrf + "&id=" + scoped.id() + "&bucket=photos&prefix=images%2F&read=on&list=on"));
            status(303, action(client, base, "update-key", cookie, base,
                "csrf=" + adminCsrf + "&id=" + scoped.id() + "&bucket=photos&prefix=images%2F&read=on&list=on" +
                "&confirm=" + scoped.id()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(s3Base + "/photos/images/new.txt"),
                "PUT", "new".getBytes(StandardCharsets.UTF_8), Map.of(), scoped.id(), scoped.secret()),
                HttpResponse.BodyHandlers.ofString()));
            status(303, action(client, base, "delete-key", cookie, base,
                "csrf=" + adminCsrf + "&id=" + scoped.id() + "&confirm=" + scoped.id()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(s3Base + "/photos/images/cat.txt"),
                "GET", new byte[0], Map.of(), scoped.id(), scoped.secret()),
                HttpResponse.BodyHandlers.ofString()));
            if (store.bucket("archive").ownership() != ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED ||
                !store.bucket("archive").publicAccessBlock().equals(ObjectStorage.PublicAccessBlock.secure()))
                throw new AssertionError("Created bucket did not have private defaults");
            status(400, action(client, base, "public-access", cookie, base,
                "csrf=" + adminCsrf + "&bucket=archive"));
            status(303, action(client, base, "public-access", cookie, base,
                "csrf=" + adminCsrf + "&bucket=archive&confirm=archive"));
            status(303, action(client, base, "ownership", cookie, base,
                "csrf=" + adminCsrf + "&bucket=archive&ownership=BucketOwnerPreferred&confirm=archive"));
            String publicPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":{\"Effect\":\"Allow\",\"Principal\":\"*\"," +
                "\"Action\":\"s3:GetObject\",\"Resource\":\"arn:aws:s3:::archive/*\"}}";
            String policyForm = "csrf=" + adminCsrf + "&bucket=archive&policy=" +
                java.net.URLEncoder.encode(publicPolicy, StandardCharsets.UTF_8);
            status(400, action(client, base, "policy", cookie, base, policyForm));
            String invalidPolicy = "{\"Version\": invalid}";
            var policyError = action(client, base, "policy", cookie, base,
                "csrf=" + adminCsrf + "&bucket=archive&policy=" +
                java.net.URLEncoder.encode(invalidPolicy, StandardCharsets.UTF_8));
            status(400, policyError);
            if (!policyError.body().contains("biscuit-codeview") ||
                !policyError.body().contains("Version&amp;quot;") &&
                !policyError.body().contains("Version&quot;"))
                throw new AssertionError("Invalid policy draft was not returned to the editor");
            status(303, action(client, base, "policy", cookie, base, policyForm + "&confirm=archive"));
            status(303, action(client, base, "policy", cookie, base,
                "csrf=" + adminCsrf + "&bucket=archive&policy="));
            status(400, action(client, base, "bucket-grant", cookie, base,
                "csrf=" + adminCsrf + "&bucket=archive&principal=AllUsers&permission=READ&operation=add"));
            status(303, action(client, base, "bucket-grant", cookie, base,
                "csrf=" + adminCsrf + "&bucket=archive&principal=AllUsers&permission=READ&operation=add&confirm=archive"));
            if (!store.bucket("archive").acl().containsKey(Acl.ALL_USERS))
                throw new AssertionError("Admin ACL grant was not persisted");
            status(303, action(client, base, "bucket-grant", cookie, base,
                "csrf=" + adminCsrf + "&bucket=archive&principal=AllUsers&operation=remove"));
            status(303, action(client, base, "versioning", cookie, base,
                "csrf=" + adminCsrf + "&bucket=archive&state=ENABLED"));
            store.createBucket("editor");
            store.setVersioning("editor", ObjectStorage.VersioningState.ENABLED);
            String versionedUpload = "bucket=editor&key=up.txt";
            byte[] oldUpload = "first".getBytes(StandardCharsets.UTF_8);
            byte[] newUpload = "second".getBytes(StandardCharsets.UTF_8);
            status(201, upload(client, base, versionedUpload, cookie, base, adminCsrf, oldUpload));
            ObjectStorage.Metadata oldUploadMeta;
            try (ObjectStorage.OpenObject opened = store.open("editor", "up.txt")) {
                oldUploadMeta = opened.metadata();
            }
            status(412, upload(client, base, versionedUpload, cookie, base, adminCsrf, newUpload));
            status(201, upload(client, base, versionedUpload + "&overwrite=1", cookie, base,
                adminCsrf, newUpload));
            var oldDownload = client.send(request(base, "/download?" + versionedUpload +
                "&version=" + java.net.URLEncoder.encode(oldUploadMeta.versionId(), StandardCharsets.UTF_8))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            status(200, oldDownload);
            if (!java.util.Arrays.equals(oldUpload, oldDownload.body()))
                throw new AssertionError("Replacing an object lost its prior version");
            byte[] firstText = "{\"hello\":1}".getBytes(StandardCharsets.UTF_8);
            ObjectStorage.Metadata firstVersion = store.put("editor", "note.json",
                new ByteArrayInputStream(firstText), firstText.length,
                SigV4.hex(SigV4.hash(firstText)), null, false, "application/json");
            var editor = client.send(request(base, "/object?bucket=editor&key=note.json")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            status(200, editor);
            if (!editor.body().contains("class=text-editor language='json'") ||
                !editor.body().contains("Save new version"))
                throw new AssertionError("Editable text object CodeView is missing");
            String editForm = "csrf=" + adminCsrf + "&bucket=editor&key=note.json&originalVersion=" +
                java.net.URLEncoder.encode(firstVersion.versionId(), StandardCharsets.UTF_8) +
                "&originalSha256=" + SigV4.hex(firstVersion.sha256()) + "&content=" +
                java.net.URLEncoder.encode("{\"hello\":2}", StandardCharsets.UTF_8);
            status(303, action(client, base, "edit-object", cookie, base, editForm));
            try (ObjectStorage.OpenObject saved = store.open("editor", "note.json")) {
                if (!new String(saved.stream().readAllBytes(), StandardCharsets.UTF_8)
                    .equals("{\"hello\":2}"))
                    throw new AssertionError("Edited object content was not saved");
            }
            try (ObjectStorage.OpenObject previous = store.open("editor", "note.json", firstVersion.versionId())) {
                if (!new String(previous.stream().readAllBytes(), StandardCharsets.UTF_8)
                    .equals("{\"hello\":1}"))
                    throw new AssertionError("The prior object version was not retained");
            }
            var stale = action(client, base, "edit-object", cookie, base, editForm);
            status(409, stale);
            if (!stale.body().contains("The object changed") || !stale.body().contains("hello&quot;:2"))
                throw new AssertionError("Stale edit diagnostic or draft is missing");
            if (store.bucket("archive").versioning() != ObjectStorage.VersioningState.ENABLED)
                throw new AssertionError("Admin versioning change was not persisted");
            status(303, action(client, base, "object-tag", cookie, base,
                "csrf=" + adminCsrf + "&bucket=photos&key=images%2Fcat.txt&tag=kind&value=cat&operation=add"));
            if (!"cat".equals(store.tags("photos", "images/cat.txt").get("kind")))
                throw new AssertionError("Admin tag change was not persisted");
            status(400, action(client, base, "delete-object", cookie, base,
                "csrf=" + adminCsrf + "&bucket=photos&key=images%2Fcat.txt"));
            status(400, action(client, base, "delete-object", cookie, base,
                "csrf=" + adminCsrf + "&bucket=photos&key=images%2Fcat.txt&version=null" +
                "&confirm=images%2Fcat.txt"));
            status(303, action(client, base, "delete-object", cookie, base,
                "csrf=" + adminCsrf + "&bucket=photos&key=images%2Fcat.txt&confirm=images%2Fcat.txt"));
            status(404, client.send(request(base, "/object?bucket=photos&key=images%2Fcat.txt")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString()));
            status(400, action(client, base, "delete-bucket", cookie, base,
                "csrf=" + adminCsrf + "&bucket=archive"));
            status(303, action(client, base, "delete-bucket", cookie, base,
                "csrf=" + adminCsrf + "&bucket=archive&confirm=archive"));
            status(303, action(client, base, "create-bucket", cookie, base,
                "csrf=" + adminCsrf + "&bucket=rename-source"));
            byte[] renamedBody = "renamed object".getBytes(StandardCharsets.UTF_8);
            names.put("rename-source", "stored.txt", new ByteArrayInputStream(renamedBody),
                renamedBody.length, null, null, false, "text/plain");
            var sourcePage = client.send(request(base, "/bucket?name=rename-source")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            status(200, sourcePage);
            if (!sourcePage.body().contains("biscuit-accordionview") ||
                !sourcePage.body().contains("Renaming changes the bucket URL"))
                throw new AssertionError("Rename warning or controls missing");
            status(400, action(client, base, "rename-bucket", cookie, base,
                "csrf=" + adminCsrf + "&bucket=rename-source&newName=rename-target"));
            status(303, action(client, base, "rename-bucket", cookie, base,
                "csrf=" + adminCsrf + "&bucket=rename-source&newName=rename-target&confirm=rename-source"));
            status(404, client.send(request(base, "/bucket?name=rename-source")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(request(base, "/object?bucket=rename-target&key=stored.txt")
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(HttpTest.signedUriAs(URI.create(s3Base + "/rename-target/stored.txt"),
                "GET", new byte[0], Map.of(), "TESTACCESSKEY123",
                "test-secret-key-that-is-at-least-32-characters"), HttpResponse.BodyHandlers.ofString()));
            status(404, client.send(HttpTest.signedUriAs(URI.create(s3Base + "/rename-source/stored.txt"),
                "GET", new byte[0], Map.of(), "TESTACCESSKEY123",
                "test-secret-key-that-is-at-least-32-characters"), HttpResponse.BodyHandlers.ofString()));
            var settingsPage = client.send(request(base, "/settings").header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, settingsPage);
            if (!settingsPage.body().contains("Save global default") ||
                !settingsPage.body().contains("Use server default") ||
                !settingsPage.body().contains("Follow system") ||
                !settingsPage.body().contains("admin-brand-icon"))
                throw new AssertionError("Appearance settings missing");
            var initialBrand = client.send(request(base, "/branding.json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (!initialBrand.body().contains("\"defaultTheme\":\"deep-dark\""))
                throw new AssertionError("Deep dark is not the default for new browsers");
            status(400, action(client, base, "default-theme", cookie, base,
                "csrf=" + adminCsrf + "&defaultTheme=unknown"));
            status(303, action(client, base, "default-theme", cookie, base,
                "csrf=" + adminCsrf + "&defaultTheme=light"));
            if (!new AdminBranding(root.resolve("data/admin-branding.txt")).state()
                .defaultTheme().equals("light"))
                throw new AssertionError("Global default theme was not saved");
            status(303, action(client, base, "branding-name", cookie, "null",
                "csrf=" + adminCsrf + "&displayName=LunarSky+Storage"));
            var brand = client.send(request(base, "/branding.json").GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, brand);
            if (!brand.body().contains("LunarSky Storage"))
                throw new AssertionError("Server branding name was not saved");
            byte[] tinyPng = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+X5ZcAAAAASUVORK5CYII=");
            status(401, client.send(request(base, "/branding-icon/upload")
                .header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(tinyPng)).build(),
                HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(request(base, "/branding-icon/upload")
                .header("Cookie", cookie).header("Content-Type", "image/png")
                .POST(HttpRequest.BodyPublishers.ofByteArray(tinyPng)).build(),
                HttpResponse.BodyHandlers.ofString()));
            var iconRequest = request(base, "/branding-icon/upload")
                .header("Cookie", cookie).header("Origin", base).header("Content-Type", "image/png")
                .header("X-CSRF-Token", adminCsrf);
            status(415, client.send(iconRequest.POST(HttpRequest.BodyPublishers.ofString("not an image")).build(),
                HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(iconRequest.POST(HttpRequest.BodyPublishers.ofByteArray(tinyPng)).build(),
                HttpResponse.BodyHandlers.ofString()));
            var storedIcon = client.send(request(base, "/branding-icon").GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
            status(200, storedIcon);
            if (!java.util.Arrays.equals(tinyPng, storedIcon.body()) ||
                !storedIcon.headers().firstValue("Content-Type").orElse("").equals("image/png"))
                throw new AssertionError("Server branding icon was not saved");
            var persistedBrand = new AdminBranding(root.resolve("data/admin-branding.txt")).state();
            if (!persistedBrand.name().equals("LunarSky Storage") ||
                !java.util.Arrays.equals(tinyPng, persistedBrand.icon()))
                throw new AssertionError("Branding did not survive a reload");
            status(303, action(client, base, "branding-reset", cookie, base, "csrf=" + adminCsrf));
            var resetBrand = new AdminBranding(root.resolve("data/admin-branding.txt")).state();
            if (!resetBrand.name().equals(AdminBranding.DEFAULT_NAME) ||
                !resetBrand.defaultTheme().equals("light"))
                throw new AssertionError("Branding reset changed the global theme");
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
            app.setAdminConsole(AdminConsole.fromEnvironment(Map.of(
                "ADMIN_UI_ENABLED", "true", "ADMIN_UI_PASSWORD_FILE", passwordFile.toString(),
                "ADMIN_UI_PRIVATE_ORIGIN", "http://10.30.0.120:9001",
                "DATA_DIR", root.resolve("data").toString()), store));
            int port = server.getAddress().getPort();
            String privatePage = rawRequest(port, "10.30.0.120:9001", "GET", "/login", "");
            if (!privatePage.startsWith("HTTP/1.1 200") || !privatePage.contains("Admin password"))
                throw new AssertionError("Configured private VPN origin did not open the admin console");
            if (!rawRequest(port, "public.example", "GET", "/login", "").startsWith("HTTP/1.1 403"))
                throw new AssertionError("Unconfigured Host reached the private admin console");
            String form = "password=" + java.net.URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8) +
                "&csrf=" + csrf(privatePage);
            String privateLogin = rawRequest(port, "10.30.0.120:9001", "POST", "/login", form);
            if (!privateLogin.startsWith("HTTP/1.1 303") ||
                !privateLogin.contains("Set-cookie: objectstore_admin=") ||
                privateLogin.contains("; Secure"))
                throw new AssertionError("Private VPN login or cookie is invalid");
            System.out.println("Admin console tests passed");
        } finally {
            server.stop(0);
            s3Server.stop(0);
            executor.close();
            activity.close();
            store.close();
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
