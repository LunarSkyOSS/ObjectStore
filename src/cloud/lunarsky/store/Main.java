package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

public final class Main {
    private static final String CAPABILITIES_PATH = "/_objectstore/capabilities";
    private final ObjectStorage store;
    private final SigV4 authentication;
    private final S3Access access;
    private final BucketHandler bucketHandler;
    private final ObjectHandler objectHandler;
    private final ClientLimits clientLimits;
    private final Semaphore slots;
    private final Semaphore adminSlots = new Semaphore(8);
    private final String virtualHostSuffix;
    private final BrowserCors cors;
    private final KeyScopes keyScopes;
    private final RequestMetrics metrics;
    private final int maxInFlight;
    private volatile AdminConsole adminConsole;

    Main(DiskStore store, SigV4 authentication, String bucket) throws IOException {
        this(store, new MultipartStore(store), authentication, bucket);
    }

    Main(ObjectStorage store, MultipartStorage multipart, SigV4 authentication, String bucket) throws IOException {
        this(store, multipart, authentication, bucket, ClientLimits.disabled());
    }

    Main(ObjectStorage store, MultipartStorage multipart, SigV4 authentication, String bucket,
         ClientLimits clientLimits) throws IOException {
        this(store, multipart, authentication, bucket, clientLimits, 16, "");
    }

    Main(ObjectStorage store, MultipartStorage multipart, SigV4 authentication, String bucket,
         ClientLimits clientLimits, int maxInFlight, String virtualHostSuffix) throws IOException {
        this(store, multipart, authentication, bucket, clientLimits, maxInFlight,
            virtualHostSuffix, BrowserCors.disabled());
    }

    Main(ObjectStorage store, MultipartStorage multipart, SigV4 authentication, String bucket,
         ClientLimits clientLimits, int maxInFlight, String virtualHostSuffix,
         BrowserCors cors) throws IOException {
        this(store, multipart, authentication, bucket, clientLimits, maxInFlight, virtualHostSuffix,
            cors, KeyScopes.unrestricted(authentication.root()));
    }

    Main(ObjectStorage store, MultipartStorage multipart, SigV4 authentication, String bucket,
         ClientLimits clientLimits, int maxInFlight, String virtualHostSuffix,
         BrowserCors cors, KeyScopes keyScopes) throws IOException {
        this(store, multipart, authentication, bucket, clientLimits, maxInFlight,
            virtualHostSuffix, cors, keyScopes, new RequestMetrics(null));
    }

    Main(ObjectStorage store, MultipartStorage multipart, SigV4 authentication, String bucket,
         ClientLimits clientLimits, int maxInFlight, String virtualHostSuffix,
         BrowserCors cors, KeyScopes keyScopes, RequestMetrics metrics) throws IOException {
        this(store, multipart, authentication, bucket, clientLimits, maxInFlight, virtualHostSuffix,
            cors, keyScopes, metrics, Map.of());
    }

    Main(ObjectStorage store, MultipartStorage multipart, SigV4 authentication, String bucket,
         ClientLimits clientLimits, int maxInFlight, String virtualHostSuffix,
         BrowserCors cors, KeyScopes keyScopes, RequestMetrics metrics,
         Map<String, BucketPolicy> identityPolicies) throws IOException {
        this(store, multipart, authentication, bucket, clientLimits, maxInFlight, virtualHostSuffix,
            cors, keyScopes, metrics, identityPolicies, null);
    }

    Main(ObjectStorage store, MultipartStorage multipart, SigV4 authentication, String bucket,
         ClientLimits clientLimits, int maxInFlight, String virtualHostSuffix,
         BrowserCors cors, KeyScopes keyScopes, RequestMetrics metrics,
         Map<String, BucketPolicy> identityPolicies, URI publicEndpoint) throws IOException {
        if (maxInFlight < 1 || maxInFlight > 1024)
            throw new IllegalArgumentException("MAX_IN_FLIGHT_REQUESTS must be between 1 and 1024");
        this.store = store;
        this.authentication = authentication;
        this.access = new S3Access(store, authentication.root(), identityPolicies);
        MultipartHandler multipartHandler = new MultipartHandler(store, multipart, authentication, access,
            publicEndpoint);
        this.bucketHandler = new BucketHandler(store, multipart, multipartHandler,
            authentication, access, bucket);
        this.objectHandler = new ObjectHandler(store, multipartHandler, authentication, access);
        this.clientLimits = clientLimits;
        this.slots = new Semaphore(maxInFlight);
        this.maxInFlight = maxInFlight;
        this.virtualHostSuffix = validateVirtualHostSuffix(virtualHostSuffix);
        this.cors = cors;
        this.keyScopes = keyScopes;
        this.metrics = metrics;
        store.ensureBucket(bucket);
    }

    void setAdminConsole(AdminConsole adminConsole) {
        this.adminConsole = adminConsole;
        if (adminConsole != null) adminConsole.setMetrics(metrics);
    }

    void handle(HttpExchange exchange) throws IOException {
        long started = System.nanoTime();
        boolean admitted = false;
        boolean dataStarted = false;
        TrafficMeter traffic = null;
        ClientLimits.Client client = null;
        String requestId = UUID.randomUUID().toString();
        exchange.getResponseHeaders().set("x-amz-request-id", requestId);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        try {
            String path = exchange.getRequestURI().getRawPath();
            if (!(path.equals(AdminConsole.PATH) || path.startsWith(AdminConsole.PATH + "/") ||
                path.equals("/health") || path.equals("/ready") || path.equals("/metrics"))) {
                traffic = TrafficMeter.attach(exchange);
                metrics.dataStarted();
                dataStarted = true;
            }
            if (path.equals(AdminConsole.PATH) || path.startsWith(AdminConsole.PATH + "/")) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            cors.decorate(exchange);
            client = clientLimits.enter(exchange);
            admitted = slots.tryAcquire();
            if (!admitted) throw new StoreException(503, "SlowDown", "Too many concurrent requests");
            if (cors.preflight(exchange)) return;
            if (handleStatus(exchange)) return;
            dispatch(exchange);
        } catch (StoreException error) {
            sendStoreError(exchange, error, requestId);
        } catch (Exception error) {
            System.err.println("ObjectStore request failed: " + requestId + " " + error.getClass().getSimpleName());
            S3Xml.sendError(exchange, 500, "InternalError", "Storage operation failed", requestId);
        } finally {
            try { exchange.close(); }
            finally {
                if (admitted) slots.release();
                clientLimits.leave(client);
                long elapsed = System.nanoTime() - started;
                metrics.record(exchange.getResponseCode(), elapsed);
                if (dataStarted)
                    metrics.dataFinished(exchange.getResponseCode(), elapsed,
                        traffic.inbound(), traffic.outbound());
            }
        }
    }

    void handleAdmin(HttpExchange exchange) throws IOException {
        boolean admitted = false;
        try {
            String path = exchange.getRequestURI().getRawPath();
            if (adminConsole == null ||
                !(path.equals(AdminConsole.PATH) || path.startsWith(AdminConsole.PATH + "/"))) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            admitted = adminSlots.tryAcquire();
            if (!admitted) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            adminConsole.handle(exchange);
        } catch (StoreException error) {
            exchange.sendResponseHeaders(error.status, -1);
        } catch (Exception error) {
            System.err.println("ObjectStore admin request failed: " + error.getClass().getSimpleName());
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
            if (admitted) adminSlots.release();
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        SigV4.Verified verified = anonymousRead(exchange)
            ? new SigV4.Verified("UNSIGNED-PAYLOAD", exchange.getRequestURI().getRawQuery(),
                null, null, null, null, null)
            : authentication.verifyRequest(exchange.getRequestMethod(),
                exchange.getRequestURI(), exchange.getRequestHeaders());
        String path = SigV4.decode(exchange.getRequestURI().getRawPath());
        Map<String, String> query = S3Request.query(verified.applicationQuery());
        if (path.equals(CAPABILITIES_PATH)) {
            access.requireOwner(verified.principal());
            if (!exchange.getRequestMethod().equals("GET")) S3Request.unsupported("Capability operation");
            if (!query.isEmpty())
                throw new StoreException(400, "InvalidArgument", "Capability request has unsupported query parameters");
            S3Request.requireEmptyBody(exchange, verified.payload());
            capabilities(exchange);
            return;
        }
        String hostBucket = virtualHostBucket(exchange);
        if (path.equals("/") && hostBucket == null) {
            access.requireOwner(verified.principal());
            if (!exchange.getRequestMethod().equals("GET") ||
                !(query.isEmpty() || query.size() == 1 && "ListBuckets".equals(query.get("x-id"))))
                S3Request.unsupported("Service operation");
            S3Request.requireEmptyBody(exchange, verified.payload());
            bucketHandler.listBuckets(exchange);
            return;
        }
        int slash = path.indexOf('/', 1);
        String requestedBucket = hostBucket == null ?
            (slash < 0 ? path.substring(1) : path.substring(1, slash)) : hostBucket;
        if (requestedBucket.isEmpty()) throw new StoreException(404, "NoSuchBucket", "Bucket not found");
        if (hostBucket != null ? path.equals("/") : slash < 0 || slash == path.length() - 1) {
            keyScopes.checkBucket(verified.principal(), requestedBucket, exchange.getRequestMethod(), query);
            bucketHandler.handleBucket(exchange, query, verified.payload(), requestedBucket, verified.principal());
        } else {
            store.bucket(requestedBucket);
            String key = hostBucket == null ? path.substring(slash + 1) : path.substring(1);
            keyScopes.checkObject(verified.principal(), requestedBucket, key,
                exchange.getRequestMethod(), query, exchange.getRequestHeaders());
            objectHandler.handleObject(exchange, key,
                query, verified, requestedBucket);
        }
    }

    private static String validateVirtualHostSuffix(String value) {
        if (value == null || value.isBlank()) return "";
        String suffix = value.toLowerCase(Locale.ROOT);
        if (suffix.length() > 253 || !suffix.matches("[a-z0-9-]+(?:\\.[a-z0-9-]+)*") ||
            java.util.Arrays.stream(suffix.split("\\.")).anyMatch(
                label -> label.length() > 63 || label.startsWith("-") || label.endsWith("-")))
            throw new IllegalArgumentException("Invalid S3_VIRTUAL_HOST_SUFFIX");
        return suffix;
    }

    private String virtualHostBucket(HttpExchange exchange) {
        if (virtualHostSuffix.isEmpty()) return null;
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null) throw new StoreException(400, "InvalidRequest", "Missing Host header");
        host = host.toLowerCase(Locale.ROOT).replaceFirst(":\\d+$", "");
        String ending = "." + virtualHostSuffix;
        if (!host.endsWith(ending)) return null;
        String bucketName = host.substring(0, host.length() - ending.length());
        if (!bucketName.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))
            throw new StoreException(400, "InvalidRequest", "Invalid bucket hostname");
        return bucketName;
    }

    private void sendStoreError(HttpExchange exchange, StoreException error, String requestId) throws IOException {
        if (error.status == 503 && error.code.equals("SlowDown"))
            exchange.getResponseHeaders().set("Retry-After", "1");
        if (anonymousRead(exchange) && error.status == 404)
            error = new StoreException(403, "AccessDenied", "Access denied");
        if (error.deleteMarker) {
            exchange.getResponseHeaders().set("x-amz-delete-marker", "true");
            exchange.getResponseHeaders().set("x-amz-version-id", error.versionId);
            if (error.modified >= 0) exchange.getResponseHeaders().set("Last-Modified",
                DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
                    .format(Instant.ofEpochMilli(error.modified)));
        }
        S3Xml.sendError(exchange, error.status, error.code, error.getMessage(), requestId);
    }

    private static boolean anonymousRead(HttpExchange exchange) {
        if (!exchange.getRequestMethod().equals("GET") &&
            !exchange.getRequestMethod().equals("HEAD")) return false;
        if (exchange.getRequestHeaders().containsKey("authorization")) return false;
        if (exchange.getRequestHeaders().keySet().stream().anyMatch(
            name -> name.toLowerCase(Locale.ROOT).startsWith("x-amz-"))) return false;
        String query = exchange.getRequestURI().getRawQuery();
        return query == null || !query.toLowerCase(Locale.ROOT).contains("x-amz-");
    }

    private boolean handleStatus(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) return false;
        String path = exchange.getRequestURI().getRawPath();
        if (path.equals("/metrics") && metrics.enabled()) {
            if (!metrics.authorized(exchange.getRequestHeaders().getFirst("Authorization")))
                throw new StoreException(401, "AccessDenied", "Metrics token is required");
            byte[] body = metrics.render(maxInFlight - slots.availablePermits())
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            return true;
        }
        if (path.equals("/health")) {
            byte[] body = "{\"status\":\"ok\",\"service\":\"lunarsky-objectstore\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            return true;
        }
        if (!path.equals("/ready")) return false;
        boolean ready = store.ready();
        byte[] body = (ready ? "ready" : "unavailable").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(ready ? 200 : 503, body.length);
        exchange.getResponseBody().write(body);
        return true;
    }

    private void capabilities(HttpExchange exchange) throws IOException {
        ObjectStorage.Limits limits = store.limits();
        String mode = store instanceof ClusterStore ? "cluster" : "disk";
        String body = """
            {
              "schemaVersion": 1,
              "service": "lunarsky-objectstore",
              "serviceVersion": "%s",
              "storageMode": "%s",
              "operations": [
                "ListBuckets", "CreateBucket", "HeadBucket", "DeleteBucket", "ListObjectsV2",
                "PutObject", "GetObject", "HeadObject", "DeleteObject", "CopyObject",
                "GetObjectTagging", "PutObjectTagging", "DeleteObjectTagging",
                "CreateMultipartUpload", "UploadPart", "ListParts", "CompleteMultipartUpload",
                "AbortMultipartUpload", "ListMultipartUploads",
                "GetBucketVersioning", "PutBucketVersioning", "ListObjectVersions",
                "GetBucketAcl", "PutBucketAcl", "GetObjectAcl", "PutObjectAcl"
              ],
              "limits": {
                "maxObjectBytes": %d,
                "maxTotalBytes": %d,
                "maxParts": 10000
              }
            }
            """.formatted(Version.VALUE, mode, limits.maxObjectBytes(), limits.maxTotalBytes());
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    public static void main(String[] args) throws Exception {
        ObjectStoreServer.start();
    }
}
