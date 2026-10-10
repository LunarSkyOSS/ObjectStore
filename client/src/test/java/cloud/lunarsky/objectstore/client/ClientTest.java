package cloud.lunarsky.objectstore.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ClientTest {
    private static final String S3_NS = "http://s3.amazonaws.com/doc/2006-03-01/";

    public static void main(String[] args) throws Exception {
        signingVector();
        protocol();
        System.out.println("Client tests passed");
    }

    private static void signingVector() {
        URI uri = URI.create("https://examplebucket.s3.amazonaws.com/test.txt");
        String empty = SigV4.hash(new byte[0]);
        String actual = SigV4.authorization("GET", uri, Map.of(
            "host", "examplebucket.s3.amazonaws.com", "range", "bytes=0-9",
            "x-amz-content-sha256", empty, "x-amz-date", "20130524T000000Z"), empty,
            Instant.parse("2013-05-24T00:00:00Z"), "us-east-1", "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        check(actual.endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"),
            "AWS Signature V4 vector");
        check("a=%20&z=%2F".equals(SigV4.query(Map.of("z", "/", "a", " "))), "query encoding");
    }

    private static void protocol() throws Exception {
        AtomicReference<String> observedHash = new AtomicReference<>();
        AtomicReference<String> observedAcl = new AtomicReference<>();
        AtomicReference<String> manifest = new AtomicReference<>();
        AtomicInteger manifestStatus = new AtomicInteger(200);
        AtomicBoolean health = new AtomicBoolean(true);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { handle(exchange, observedHash, observedAcl, manifest, manifestStatus, health); }
            finally { exchange.close(); }
        });
        server.start();
        try (ObjectStorageClient client = new ObjectStorageClientBuilder()
            .endpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
            .allowInsecureHttp().credentials("test-key", "test-secret").build()) {
            client.putObject("mybucket", "hello world.txt", "hello".getBytes(StandardCharsets.UTF_8), "text/plain");
            check(SigV4.hash("hello".getBytes(StandardCharsets.UTF_8)).equals(observedHash.get()), "payload hash");
            try (ObjectStorageClient.ObjectData data = client.getObject("mybucket", "hello world.txt")) {
                check("hello".equals(new String(data.body().readAllBytes(), StandardCharsets.UTF_8)), "GET body");
            }
            check("text/plain".equals(client.headObject("mybucket", "hello world.txt").contentType()),
                "HEAD content type");
            var page = client.listObjects("mybucket", "hello", null, 10);
            check(page.objects().size() == 1 && "hello world.txt".equals(page.objects().getFirst().key()),
                "list object key");
            check("next-token".equals(page.nextContinuationToken()), "list continuation");
            AclPolicy policy = client.getBucketAcl("mybucket");
            check(policy.grants().size() == 1 && policy.grants().getFirst().permission() ==
                AclPolicy.Permission.FULL_CONTROL, "GET ACL");
            client.putObjectAcl("mybucket", "hello world.txt", new AclPolicy("owner-id", List.of(
                new AclPolicy.Grant(AclPolicy.GranteeType.GROUP,
                    "http://acs.amazonaws.com/groups/global/AllUsers", AclPolicy.Permission.READ))));
            check(observedAcl.get().contains("<Permission>READ</Permission>"), "PUT ACL");
            check(client.getCapabilities().service() == Capabilities.ServiceKind.OBJECTSTORE, "service discovery");
            check(client.getCapabilities().support("versioning") == Capabilities.Support.UNKNOWN,
                "unknown support remains unknown");
            manifest.set("{\"schemaVersion\":1,\"service\":\"lunarsky-objectstore\"," +
                "\"serviceVersion\":\"0.0.5\",\"storageMode\":\"disk\"," +
                "\"operations\":[\"PutObject\",\"ListParts\"]," +
                "\"limits\":{\"maxObjectBytes\":1024,\"maxTotalBytes\":4096,\"maxParts\":10000}}");
            var capabilities = client.getCapabilities();
            check(capabilities.service() == Capabilities.ServiceKind.OBJECTSTORE &&
                capabilities.support("ListParts") == Capabilities.Support.SUPPORTED &&
                capabilities.support("PutBucketAcl") == Capabilities.Support.UNSUPPORTED &&
                capabilities.limits().get("maxParts") == 10000 &&
                "0.0.5".equals(capabilities.serviceVersion()), "signed manifest");
            manifest.set("{\"schemaVersion\":1,\"schemaVersion\":1}");
            try {
                client.getCapabilities();
                throw new AssertionError("Expected malformed manifest");
            } catch (ProtocolException expected) { }
            health.set(false);
            check(client.getCapabilities().service() == Capabilities.ServiceKind.UNKNOWN_S3,
                "generic S3 with unrelated matching path");
            manifestStatus.set(503);
            check(client.getCapabilities().support("CopyObject") == Capabilities.Support.UNKNOWN,
                "ambiguous manifest failure stays unknown");
            manifestStatus.set(200);
            manifest.set(null);
            check(client.getCapabilities().service() == Capabilities.ServiceKind.UNKNOWN_S3, "generic S3 fallback");
            check(client.getCapabilities().support("PutObject") == Capabilities.Support.SUPPORTED &&
                client.getCapabilities().support("CopyObject") == Capabilities.Support.UNKNOWN,
                "generic S3 observed support");
            capabilities = client.probeReadOnlyCapabilities("mybucket", "hello world.txt");
            check(capabilities.support("ListMultipartUploads") == Capabilities.Support.UNKNOWN &&
                capabilities.support("ListObjectsV2") == Capabilities.Support.SUPPORTED,
                "denied generic probe remains unknown");
            client.deleteObject("mybucket", "hello world.txt");
            try {
                client.getObject("mybucket", "denied");
                throw new AssertionError("Expected AccessDenied");
            } catch (ObjectStorageException error) {
                check(error.statusCode() == 403 && "AccessDenied".equals(error.errorCode()) &&
                    "request-123".equals(error.requestId()), "typed S3 error");
            }
        } finally { server.stop(0); }
    }

    private static void handle(HttpExchange exchange, AtomicReference<String> hash,
                               AtomicReference<String> acl, AtomicReference<String> manifest,
                               AtomicInteger manifestStatus, AtomicBoolean health) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        if ("/health".equals(path)) {
            respond(exchange, health.get() ? 200 : 404,
                health.get() ? "{\"status\":\"ok\",\"service\":\"lunarsky-objectstore\"}" : "");
            return;
        }
        check(exchange.getRequestHeaders().getFirst("Authorization") != null, "signed request");
        if ("/_objectstore/capabilities".equals(path)) {
            String value = manifest.get();
            respond(exchange, value == null ? 404 : manifestStatus.get(), value == null ? "" : value);
            return;
        }
        if ("/mybucket".equals(path) && query != null && query.contains("uploads=")) {
            respond(exchange, 403, "<Error><Code>AccessDenied</Code></Error>");
            return;
        }
        if ("/mybucket".equals(path) && query != null && query.contains("list-type=2")) {
            respond(exchange, 200, "<ListBucketResult xmlns=\"" + S3_NS + "\"><Contents><Key>hello%20world.txt</Key>" +
                "<Size>5</Size><ETag>\"abc\"</ETag></Contents><NextContinuationToken>next-token" +
                "</NextContinuationToken></ListBucketResult>");
            return;
        }
        if (query != null && query.equals("acl=")) {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "<AccessControlPolicy xmlns=\"" + S3_NS + "\" " +
                    "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><Owner><ID>owner-id</ID></Owner>" +
                    "<AccessControlList><Grant><Grantee xsi:type=\"CanonicalUser\"><ID>owner-id</ID>" +
                    "</Grantee><Permission>FULL_CONTROL</Permission></Grant></AccessControlList></AccessControlPolicy>");
            } else {
                acl.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                check(exchange.getRequestHeaders().getFirst("Content-MD5") != null, "ACL Content-MD5");
                respond(exchange, 200, "");
            }
            return;
        }
        if ("/mybucket/denied".equals(path)) {
            exchange.getResponseHeaders().add("x-amz-request-id", "request-123");
            respond(exchange, 403, "<Error><Code>AccessDenied</Code></Error>");
            return;
        }
        check("/mybucket/hello%20world.txt".equals(path), "path-style key encoding");
        switch (exchange.getRequestMethod()) {
            case "PUT" -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                hash.set(exchange.getRequestHeaders().getFirst("x-amz-content-sha256"));
                check(SigV4.hash(body).equals(hash.get()), "uploaded body hash");
                respond(exchange, 200, "");
            }
            case "GET" -> respond(exchange, 200, "hello");
            case "HEAD" -> {
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, -1);
            }
            case "DELETE" -> respond(exchange, 204, "");
            default -> throw new AssertionError("Unexpected method");
        }
    }

    private static void respond(HttpExchange exchange, int status, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (status == 204 || bytes.length == 0) exchange.sendResponseHeaders(status, -1);
        else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
