package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public final class RequestCoordinationTest {
    private static final String PASSWORD = "a-long-admin-password-for-tests";

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("request-coordination-");
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        AtomicBoolean blockReady = new AtomicBoolean();
        AtomicReference<StoreOperations.Action> bucketAction = new AtomicReference<>();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        HttpServer admin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        try (DiskStore disk = new DiskStore(root.resolve("data"), 1024, 4096)) {
            DiskBucketNames names = new DiskBucketNames(disk, new MultipartStore(disk));
            ObjectStorage store = (ObjectStorage) Proxy.newProxyInstance(
                ObjectStorage.class.getClassLoader(), new Class<?>[] {ObjectStorage.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("bucket")) {
                        StoreOperations.Action action = bucketAction.getAndSet(null);
                        if (action != null) action.run();
                    }
                    if (method.getName().equals("ready") && blockReady.get()) {
                        readStarted.countDown();
                        if (!releaseRead.await(5, TimeUnit.SECONDS))
                            throw new IOException("Read was not released");
                    }
                    try {
                        return method.invoke(names, arguments);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
            Path password = root.resolve("password");
            Files.writeString(password, PASSWORD);
            AdminConsole console = AdminConsole.fromEnvironment(Map.of(
                "ADMIN_UI_ENABLED", "true", "ADMIN_UI_PASSWORD_FILE", password.toString(),
                "DATA_DIR", root.resolve("data").toString()), store);
            Main app = new Main(store, names, new SigV4("TESTACCESSKEY123",
                "test-secret-key-that-is-at-least-32-characters", "us-east-1", Clock.systemUTC()),
                "objects");
            app.setAdminConsole(console);
            server.setExecutor(executor);
            server.createContext("/", app::handle);
            admin.setExecutor(executor);
            admin.createContext("/", app::handleAdmin);
            server.start();
            admin.start();
            try (HttpClient client = HttpClient.newHttpClient()) {
                String base = "http://127.0.0.1:" + server.getAddress().getPort();
                String adminBase = "http://127.0.0.1:" + admin.getAddress().getPort();
                String loginPage = send(client, request(adminBase + AdminConsole.PATH + "/login").GET()).body();
                HttpResponse<String> login = send(client,
                    form(adminBase + AdminConsole.PATH + "/login", "password=" + PASSWORD +
                        "&csrf=" + csrf(loginPage)));
                status(login, 303);
                String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
                String overview = send(client, request(adminBase + AdminConsole.PATH + "/overview")
                    .header("Cookie", cookie).GET()).body();
                String token = csrf(overview);
                verifyRenameIsolation(client, base, adminBase, cookie, token,
                    blockReady, readStarted, releaseRead);
                verifyDiagnostics(client, base, adminBase, cookie);
                verifyRevocation(client, base, app, names, disk, root, bucketAction);
            }
        } finally {
            releaseRead.countDown();
            server.stop(0);
            admin.stop(0);
            executor.close();
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("Request coordination tests passed");
    }

    private static void verifyRenameIsolation(HttpClient client, String base, String adminBase,
                                              String cookie, String token, AtomicBoolean blockReady,
                                              CountDownLatch readStarted, CountDownLatch releaseRead)
        throws Exception {
        blockReady.set(true);
        var heldRead = client.sendAsync(request(base + "/ready").GET().build(),
            HttpResponse.BodyHandlers.ofString());
        if (!readStarted.await(2, TimeUnit.SECONDS)) throw new AssertionError("Read did not start");
        String url = adminBase + AdminConsole.PATH + "/action/rename-bucket";
        status(send(client, form(url, "bucket=objects&newName=renamed&confirm=objects")), 303);
        status(send(client, form(url, "bucket=objects&newName=renamed&confirm=objects&csrf=invalid")
            .header("Cookie", cookie)), 403);
        status(send(client, form(url, "bucket=objects&newName=renamed&confirm=objects&csrf=" + token)
            .header("Cookie", cookie)), 409);
        status(send(client, request(base + "/health").GET()), 200);
        var rename = client.sendAsync(form(url, "bucket=objects&newName=renamed&confirm=objects&csrf=" + token)
            .header("Cookie", cookie).build(), HttpResponse.BodyHandlers.ofString());
        Thread.sleep(100);
        if (rename.isDone()) throw new AssertionError("Rename did not wait for the active storage request");
        blockReady.set(false);
        releaseRead.countDown();
        status(heldRead.get(2, TimeUnit.SECONDS), 200);
        status(rename.get(2, TimeUnit.SECONDS), 303);
    }

    private static void verifyDiagnostics(HttpClient client, String base, String adminBase,
                                          String cookie) throws Exception {
        HttpResponse<String> signed = client.send(HttpTest.signedUri(
            URI.create(base + "/renamed/missing"), "GET", new byte[0], Map.of()),
            HttpResponse.BodyHandlers.ofString());
        status(signed, 404);
        HttpResponse<String> failed = send(client, request(base + "/other/missing")
            .header("Authorization", "invalid").GET());
        status(failed, 403);
        String requestId = failed.headers().firstValue("x-amz-request-id").orElseThrow();
        String page = "";
        for (int attempt = 0; attempt < 20; attempt++) {
            page = send(client, request(adminBase + AdminConsole.PATH + "/diagnostics")
                .header("Cookie", cookie).GET()).body();
            if (page.contains(requestId)) break;
            Thread.sleep(10);
        }
        var row = Pattern.compile("<tr>(?:(?!</tr>).)*" + Pattern.quote(requestId) +
            "(?:(?!</tr>).)*</tr>", Pattern.DOTALL).matcher(page);
        if (!row.find() || !row.group().contains("Service") || row.group().contains("renamed"))
            throw new AssertionError("Failed authentication inherited another request's bucket or operation");
    }

    private static void verifyRevocation(HttpClient client, String base, Main app, DiskBucketNames store,
                                         DiskStore disk, Path root,
                                         AtomicReference<StoreOperations.Action> bucketAction) throws Exception {
        Path master = root.resolve("master-key");
        Files.writeString(master, "0123456789abcdef".repeat(4));
        ManagedCredentials managed = ManagedCredentials.fromEnvironment(Map.of(
            "S3_MANAGED_KEYS_MASTER_FILE", master.toString(), "DATA_DIR", root.resolve("data").toString()),
            disk, Set.of("TESTACCESSKEY123"));
        ManagedCredentials.Key key = managed.create("renamed", "private/", Set.of("read"));
        app.setManagedCredentials(managed);
        byte[] value = {1};
        store.put("renamed", "outside.txt", new java.io.ByteArrayInputStream(value), value.length,
            SigV4.hex(SigV4.hash(value)), null, true, "text/plain");
        store.setBucketPolicy("renamed", "{\"Version\":\"2012-10-17\",\"Statement\":[{" +
            "\"Effect\":\"Allow\",\"Principal\":\"" + key.id() + "\",\"Action\":\"s3:GetObject\"," +
            "\"Resource\":\"arn:aws:s3:::renamed/*\"}]}");
        URI object = URI.create(base + "/renamed/outside.txt");
        status(client.send(HttpTest.signedUriAs(object, "GET", new byte[0], Map.of(), key.id(), key.secret()),
            HttpResponse.BodyHandlers.ofString()), 403);
        bucketAction.set(() -> managed.remove(key.id()));
        status(client.send(HttpTest.signedUriAs(object, "GET", new byte[0], Map.of(), key.id(), key.secret()),
            HttpResponse.BodyHandlers.ofString()), 403);
        if (managed.contains(key.id())) throw new AssertionError("Key revocation did not happen during request");
    }

    private static HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(2));
    }

    private static HttpRequest.Builder form(String url, String body) {
        return request(url).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request)
        throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String csrf(String page) {
        var token = Pattern.compile("name=csrf value='([A-Za-z0-9_-]{43})'").matcher(page);
        if (!token.find()) throw new AssertionError("Missing CSRF token");
        return token.group(1);
    }

    private static void status(HttpResponse<?> response, int expected) {
        if (response.statusCode() != expected)
            throw new AssertionError("Expected " + expected + ", got " + response.statusCode());
    }
}
