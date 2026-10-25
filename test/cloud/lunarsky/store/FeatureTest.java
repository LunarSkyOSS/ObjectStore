package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

public final class FeatureTest {
    private static final String OWNER = "TESTACCESSKEY123";
    private static final String LIMITED = "LIMITEDACCESS1234";
    private static final String OWNER_SECRET = "test-secret-key-that-is-at-least-32-characters";
    private static final String LIMITED_SECRET = "limited-secret-key-that-is-at-least-32-characters";
    private static final String SESSION = "SESSIONACCESS1234";
    private static final String SESSION_SECRET = "session-secret-key-that-is-at-least-32-characters";
    private static final String SESSION_TOKEN = "session-token-that-is-at-least-32-characters";

    private static void status(int expected, HttpResponse<?> response) {
        if (response.statusCode() != expected)
            throw new AssertionError("Expected " + expected + ", got " + response.statusCode() + ": " + response.body());
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("objectstore-feature-test-");
        Path sessionFile = root.resolve("sessions");
        Files.writeString(sessionFile, SESSION + "\t" + SESSION_SECRET + "\t" + SESSION_TOKEN +
            "\t" + java.time.Instant.now().plusSeconds(3600).getEpochSecond() + "\n");
        var sessions = SessionCredentials.fromEnvironment(
            Map.of("S3_SESSION_CREDENTIALS_FILE", sessionFile.toString()), Set.of(OWNER, LIMITED));
        if (!sessions.containsKey(SESSION)) throw new AssertionError("Session credentials not loaded");
        Path identityDir = Files.createDirectory(root.resolve("identity-policies"));
        Files.writeString(identityDir.resolve(LIMITED + ".json"),
            "{\"Version\":\"2012-10-17\",\"Statement\":[" +
            statement("Deny", null, "s3:PutObject", "arn:aws:s3:::photos/users/one/blocked") + "," +
            statement("Allow", null, "s3:GetObject", "arn:aws:s3:::photos/users/one/self_readable") + "," +
            statement("Allow", null, "s3:PutObject", "arn:aws:s3:::secure/users/one/*") + "," +
            statement("Allow", null, "s3:GetObject", "arn:aws:s3:::secure/users/one/*") + "]}");
        Files.writeString(identityDir.resolve(SESSION + ".json"), policy("Allow", null,
            "s3:GetObject", "arn:aws:s3:::photos/users/one/allowed"));
        var identityPolicies = IdentityPolicies.fromEnvironment(
            Map.of("S3_IDENTITY_POLICIES_DIR", identityDir.toString()), OWNER,
            Set.of(OWNER, LIMITED, SESSION));
        Path policy = root.resolve("scopes");
        Files.writeString(policy, LIMITED + "\tphotos\tusers/one/\tread,write,list\n" +
            LIMITED + "\tsecure\tusers/one/\tread,write\n" +
            SESSION + "\tphotos\tusers/one/\tread\n");
        KeyScopes scopes = KeyScopes.fromEnvironment(Map.of("S3_KEY_SCOPES_FILE", policy.toString()),
            OWNER, Set.of(OWNER, LIMITED, SESSION));
        scopes.require(LIMITED, "write", "photos", "users/one/image.jpg");
        scopes.checkBucket(LIMITED, "photos", "GET", Map.of("list-type", "2", "prefix", "users/one/"));
        denied(() -> scopes.require(LIMITED, "read", "photos", "users/two/image.jpg"));
        denied(() -> scopes.checkBucket(LIMITED, "photos", "GET", Map.of("list-type", "2")));
        denied(() -> scopes.checkBucket(LIMITED, "photos", "GET", Map.of("list-type", "2", "prefix", "users/")));
        denied(() -> scopes.checkObject(LIMITED, "photos", "users/one/copy.jpg", "PUT", Map.of(),
            headers("x-amz-copy-source", "/photos/users/two/private.jpg")));
        denied(() -> scopes.checkObject(LIMITED, "photos", "users/one/file", "PUT", Map.of(),
            headers("x-amz-acl", "public-read")));
        var cors = BrowserCors.fromEnvironment(Map.of("CORS_ALLOWED_ORIGINS", "https://dashboard.example"));
        var metrics = new RequestMetrics("metrics-test-token-at-least-32-characters");
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        DiskStore store = new DiskStore(root.resolve("data"), 1024, 4096);
        try {
            Main app = new Main(store, new MultipartStore(store),
                new SigV4(Map.of(OWNER, OWNER_SECRET, LIMITED, LIMITED_SECRET), sessions, OWNER,
                    "us-east-1", Clock.systemUTC()),
                "photos", ClientLimits.disabled(), 16, "", cors, scopes, metrics, identityPolicies);
            store.setBucketOwnership("photos", ObjectStorage.ObjectOwnership.OBJECT_WRITER);
            store.setPublicAccessBlock("photos", ObjectStorage.PublicAccessBlock.open());
            server.setExecutor(executor);
            server.createContext("/", app::handle);
            server.start();
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpClient client = HttpClient.newHttpClient();
            status(204, client.send(HttpRequest.newBuilder(URI.create(base + "/photos/file"))
                .header("Origin", "https://dashboard.example")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type,x-amz-date")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpRequest.newBuilder(URI.create(base + "/photos/file"))
                .header("Origin", "https://other.example")
                .header("Access-Control-Request-Method", "PUT")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString()));
            var health = client.send(HttpRequest.newBuilder(URI.create(base + "/health"))
                .header("Origin", "https://dashboard.example").GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, health);
            if (!"https://dashboard.example".equals(health.headers().firstValue("Access-Control-Allow-Origin").orElse(null)))
                throw new AssertionError("Allowed origin missing from response");
            status(401, client.send(HttpRequest.newBuilder(URI.create(base + "/metrics")).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            var snapshot = client.send(HttpRequest.newBuilder(URI.create(base + "/metrics"))
                .header("Authorization", "Bearer metrics-test-token-at-least-32-characters").GET().build(),
                HttpResponse.BodyHandlers.ofString());
            status(200, snapshot);
            if (!snapshot.body().contains("objectstore_requests_total 4"))
                throw new AssertionError("Metrics did not count requests: " + snapshot.body());
            status(200, client.send(HttpTest.signedUri(URI.create(base + "/photos?acl"),
                "PUT", new byte[0], Map.of("x-amz-grant-full-control", "id=\"" + LIMITED + "\"")),
                HttpResponse.BodyHandlers.ofString()));
            status(405, client.send(HttpTest.signedUri(URI.create(base + "/photos?acl"),
                "PUT", new byte[0], Map.of("x-amz-grant-read", "emailAddress=\"example@example.test\"")),
                HttpResponse.BodyHandlers.ofString()));
            byte[] content = "allowed".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            status(200, client.send(HttpTest.signedUriAs(URI.create(base + "/photos/users/one/allowed"),
                "PUT", content, Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            URI ownReadable = URI.create(base + "/photos/users/one/self_readable");
            status(200, client.send(HttpTest.signedUriAs(ownReadable,
                "PUT", content, Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(HttpTest.signedUriAs(ownReadable,
                "GET", new byte[0], Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(base + "/photos/users/one/allowed"),
                "GET", new byte[0], Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(base + "/photos/users/one/blocked"),
                "PUT", content, Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(base + "/photos/users/two/denied"),
                "PUT", content, Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(HttpTest.signedUriAs(URI.create(base + "/photos?list-type=2&prefix=users%2Fone%2F"),
                "GET", new byte[0], Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(base + "/photos?list-type=2"),
                "GET", new byte[0], Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(base + "/photos/users/one/allowed?acl"),
                "GET", new byte[0], Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(HttpTest.signedUri(URI.create(base + "/photos/users/one/allowed?acl"),
                "PUT", new byte[0], Map.of("x-amz-acl", "public-read")),
                HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(HttpRequest.newBuilder(
                URI.create(base + "/photos/users/one/allowed")).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            store.setPublicAccessBlock("photos", ObjectStorage.PublicAccessBlock.secure());
            status(403, client.send(HttpRequest.newBuilder(
                URI.create(base + "/photos/users/one/allowed")).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUri(URI.create(base + "/photos/users/one/public"),
                "PUT", content, Map.of("x-amz-acl", "public-read")),
                HttpResponse.BodyHandlers.ofString()));
            store.createBucket("secure");
            var secure = store.bucket("secure");
            if (secure.ownership() != ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED ||
                !secure.publicAccessBlock().blockPublicAcls() ||
                !secure.publicAccessBlock().ignorePublicAcls() ||
                !secure.publicAccessBlock().blockPublicPolicy() ||
                !secure.publicAccessBlock().restrictPublicBuckets())
                throw new AssertionError("New bucket did not default to private access");
            URI scopedObject = URI.create(base + "/secure/users/one/photo.jpg");
            status(200, client.send(HttpTest.signedUriAs(scopedObject,
                "PUT", content, Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(HttpTest.signedUriAs(scopedObject,
                "GET", new byte[0], Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(base + "/secure/users/two/photo.jpg"),
                "GET", new byte[0], Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(400, client.send(HttpTest.signedUri(URI.create(base + "/secure/item"),
                "PUT", content, Map.of("x-amz-acl", "public-read")),
                HttpResponse.BodyHandlers.ofString()));
            URI ownership = URI.create(base + "/photos?ownershipControls");
            byte[] enforced = ("<OwnershipControls><Rule><ObjectOwnership>BucketOwnerEnforced" +
                "</ObjectOwnership></Rule></OwnershipControls>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            status(200, client.send(HttpTest.signedUri(ownership, "PUT", enforced, Map.of()),
                HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(base + "/photos/users/one/allowed"),
                "GET", new byte[0], Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            URI bucketPolicy = URI.create(base + "/photos?policy");
            String allow = policy("Allow", LIMITED, "s3:GetObject", "arn:aws:s3:::photos/users/one/*");
            status(204, client.send(HttpTest.signedUri(bucketPolicy, "PUT", allow.getBytes(), Map.of()),
                HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(HttpTest.signedUriAs(URI.create(base + "/photos/users/one/allowed"),
                "GET", new byte[0], Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            String deny = "{\"Version\":\"2012-10-17\",\"Statement\":[" +
                statement("Allow", LIMITED, "s3:GetObject", "arn:aws:s3:::photos/users/one/*") + "," +
                statement("Deny", "*", "s3:GetObject", "arn:aws:s3:::photos/users/one/allowed") + "]}";
            status(204, client.send(HttpTest.signedUri(bucketPolicy, "PUT", deny.getBytes(), Map.of()),
                HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(URI.create(base + "/photos/users/one/allowed"),
                "GET", new byte[0], Map.of(), LIMITED, LIMITED_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUri(URI.create(base + "/photos/users/one/allowed"),
                "GET", new byte[0], Map.of()), HttpResponse.BodyHandlers.ofString()));
            String publicPolicy = policy("Allow", "*", "s3:GetObject", "arn:aws:s3:::photos/users/one/*");
            status(403, client.send(HttpTest.signedUri(bucketPolicy, "PUT", publicPolicy.getBytes(), Map.of()),
                HttpResponse.BodyHandlers.ofString()));
            byte[] block = ("<PublicAccessBlockConfiguration><BlockPublicAcls>true</BlockPublicAcls>" +
                "<IgnorePublicAcls>true</IgnorePublicAcls><BlockPublicPolicy>false</BlockPublicPolicy>" +
                "<RestrictPublicBuckets>true</RestrictPublicBuckets></PublicAccessBlockConfiguration>").getBytes();
            status(200, client.send(HttpTest.signedUri(URI.create(base + "/photos?publicAccessBlock"),
                "PUT", block, Map.of()), HttpResponse.BodyHandlers.ofString()));
            status(204, client.send(HttpTest.signedUri(bucketPolicy, "PUT", publicPolicy.getBytes(), Map.of()),
                HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpRequest.newBuilder(
                URI.create(base + "/photos/users/one/allowed")).GET().build(),
                HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(HttpTest.signedUri(bucketPolicy, "GET", new byte[0], Map.of()),
                HttpResponse.BodyHandlers.ofString()));
            String sessionPolicy = policy("Allow", LIMITED, "s3:GetObject",
                "arn:aws:s3:::photos/users/one/*");
            status(204, client.send(HttpTest.signedUri(bucketPolicy, "PUT", sessionPolicy.getBytes(), Map.of()),
                HttpResponse.BodyHandlers.ofString()));
            URI sessionObject = URI.create(base + "/photos/users/one/allowed");
            status(200, client.send(HttpTest.signedUriAs(sessionObject, "GET", new byte[0],
                Map.of("x-amz-security-token", SESSION_TOKEN), SESSION, SESSION_SECRET),
                HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(sessionObject, "GET", new byte[0],
                Map.of(), SESSION, SESSION_SECRET), HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(sessionObject, "GET", new byte[0],
                Map.of("x-amz-security-token", SESSION_TOKEN + "wrong"), SESSION, SESSION_SECRET),
                HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(sessionObject, "PUT", content,
                Map.of("x-amz-security-token", SESSION_TOKEN), SESSION, SESSION_SECRET),
                HttpResponse.BodyHandlers.ofString()));
            status(200, client.send(HttpRequest.newBuilder(HttpTest.presignedUriAs(sessionObject, "GET", 60,
                SESSION, SESSION_SECRET, SESSION_TOKEN)).GET().build(), HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpRequest.newBuilder(HttpTest.presignedUriAs(sessionObject, "GET", 60,
                SESSION, SESSION_SECRET, null)).GET().build(), HttpResponse.BodyHandlers.ofString()));
            status(403, client.send(HttpTest.signedUriAs(sessionObject, "GET", new byte[0],
                Map.of("x-amz-security-token", SESSION_TOKEN), OWNER, OWNER_SECRET),
                HttpResponse.BodyHandlers.ofString()));
            var expired = new SigV4(Map.of(OWNER, OWNER_SECRET), Map.of(SESSION,
                new SessionCredentials.Session(SESSION_SECRET, SESSION_TOKEN,
                    java.time.Instant.now().minusSeconds(1))), OWNER, "us-east-1", Clock.systemUTC());
            var signed = HttpTest.signedUriAs(sessionObject, "GET", new byte[0],
                Map.of("x-amz-security-token", SESSION_TOKEN), SESSION, SESSION_SECRET);
            var signedHeaders = new com.sun.net.httpserver.Headers();
            signed.headers().map().forEach(signedHeaders::put);
            signedHeaders.set("host", sessionObject.getAuthority());
            denied(() -> expired.verifyRequest("GET", sessionObject, signedHeaders));
        } finally {
            server.stop(0);
            executor.close();
            store.close();
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Feature tests passed: scoped keys, CORS, metrics, bucket policy and public access");
    }

    private static String policy(String effect, String principal, String action, String resource) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[" +
            statement(effect, principal, action, resource) + "]}";
    }

    private static String statement(String effect, String principal, String action, String resource) {
        return "{\"Effect\":\"" + effect + "\"," +
            (principal == null ? "" : "\"Principal\":\"" + principal + "\",") +
            "\"Action\":\"" + action + "\",\"Resource\":\"" + resource + "\"}";
    }

    private static com.sun.net.httpserver.Headers headers(String name, String value) {
        var headers = new com.sun.net.httpserver.Headers();
        headers.set(name, value);
        return headers;
    }

    private static void denied(Runnable action) {
        try { action.run(); }
        catch (StoreException error) {
            if (error.status == 403) return;
            throw new AssertionError("Wrong denial", error);
        }
        throw new AssertionError("Expected access denial");
    }
}
