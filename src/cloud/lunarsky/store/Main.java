package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public final class Main {


    //Todo: Main is too complex. Chop Chop.

    
    private static final String CAPABILITIES_PATH = "/_objectstore/capabilities";
    private final ObjectStorage store;
    private final SigV4 authentication;
    private final String owner;
    private final String bucket;
    private final MultipartStorage multipart;
    private final ClientLimits clientLimits;
    private final Semaphore slots = new Semaphore(16);

    Main(DiskStore store, SigV4 authentication, String bucket) throws IOException {
        this(store, new MultipartStore(store), authentication, bucket);
    }

    Main(ObjectStorage store, MultipartStorage multipart, SigV4 authentication, String bucket) throws IOException {
        this(store, multipart, authentication, bucket, ClientLimits.disabled());
    }

    Main(ObjectStorage store, MultipartStorage multipart, SigV4 authentication, String bucket,
         ClientLimits clientLimits) throws IOException {
        this.store = store;
        this.multipart = multipart;
        this.authentication = authentication;
        this.owner = authentication.root();
        this.bucket = bucket;
        this.clientLimits = clientLimits;
        store.ensureBucket(bucket);
    }

    //Todo: These methods dont need to be in Main.java.
    
    void handle(HttpExchange exchange) throws IOException {
        boolean admitted = false;
        ClientLimits.Client client = null;
        String requestId = UUID.randomUUID().toString();
        exchange.getResponseHeaders().set("x-amz-request-id", requestId);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        try {
            client = clientLimits.enter(exchange);
            admitted = slots.tryAcquire();
            if (!admitted) throw new StoreException(503, "SlowDown", "Too many concurrent requests");
            if (handleStatus(exchange)) return;
            dispatch(exchange);
        } catch (StoreException error) {
            sendStoreError(exchange, error, requestId);
        } catch (Exception error) {
            System.err.println("ObjectStore request failed: " + requestId + " " + error.getClass().getSimpleName());
            sendError(exchange, 500, "InternalError", "Storage operation failed", requestId);
        } finally {
            try { exchange.close(); }
            finally {
                if (admitted) slots.release();
                clientLimits.leave(client);
            }
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        SigV4.Verified verified = anonymousRead(exchange)
            ? new SigV4.Verified("UNSIGNED-PAYLOAD", exchange.getRequestURI().getRawQuery(),
                null, null, null, null, null)
            : authentication.verifyRequest(exchange.getRequestMethod(),
                exchange.getRequestURI(), exchange.getRequestHeaders());
        String path = SigV4.decode(exchange.getRequestURI().getRawPath());
        Map<String, String> query = query(verified.applicationQuery());
        if (path.equals(CAPABILITIES_PATH)) {
            requireOwner(verified.principal());
            if (!exchange.getRequestMethod().equals("GET")) unsupported("Capability operation");
            if (!query.isEmpty())
                throw new StoreException(400, "InvalidArgument", "Capability request has unsupported query parameters");
            requireEmptyBody(exchange, verified.payload());
            capabilities(exchange);
            return;
        }
        if (path.equals("/")) {
            requireOwner(verified.principal());
            if (!exchange.getRequestMethod().equals("GET") ||
                !(query.isEmpty() || query.size() == 1 && "ListBuckets".equals(query.get("x-id"))))
                unsupported("Service operation");
            requireEmptyBody(exchange, verified.payload());
            listBuckets(exchange);
            return;
        }
        int slash = path.indexOf('/', 1);
        String requestedBucket = slash < 0 ? path.substring(1) : path.substring(1, slash);
        if (requestedBucket.isEmpty()) throw new StoreException(404, "NoSuchBucket", "Bucket not found");
        if (slash < 0 || slash == path.length() - 1) {
            handleBucket(exchange, query, verified.payload(), requestedBucket, verified.principal());
        } else {
            store.bucket(requestedBucket);
            handleObject(exchange, path.substring(slash + 1), query, verified, requestedBucket);
        }
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
        sendError(exchange, error.status, error.code, error.getMessage(), requestId);
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

    private void requireOwner(String principal) {
        if (!owner.equals(principal)) throw new StoreException(403, "AccessDenied", "Access denied");
    }

    private void requireBucket(String bucket, String principal, int permission) throws IOException {
        Acl.require(store.bucket(bucket).acl(), principal, owner, permission);
    }

    private boolean handleStatus(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) return false;
        String path = exchange.getRequestURI().getRawPath();
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

    private void listBuckets(HttpExchange exchange) throws IOException {
        StringBuilder body = new StringBuilder("<ListAllMyBucketsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
            "<Owner><ID>" + owner + "</ID><DisplayName>ObjectStore</DisplayName></Owner><Buckets>");
        for (ObjectStorage.Bucket entry : store.buckets())
            body.append("<Bucket><Name>").append(xml(entry.name())).append("</Name><CreationDate>")
                .append(Instant.ofEpochMilli(entry.created())).append("</CreationDate></Bucket>");
        sendXml(exchange, 200, body.append("</Buckets></ListAllMyBucketsResult>").toString());
    }

    private void handleBucket(HttpExchange exchange, Map<String, String> query, String hash,
                              String bucketName, String principal) throws IOException {
        String method = exchange.getRequestMethod();
        if (query.containsKey("acl") && query.get("acl").isEmpty() &&
            query.keySet().stream().allMatch(java.util.Set.of("acl", "x-id")::contains) &&
            (!query.containsKey("x-id") ||
             query.get("x-id").equals(method.equals("GET") ? "GetBucketAcl" : "PutBucketAcl")) &&
            (method.equals("GET") || method.equals("PUT"))) {
            var current = store.bucket(bucketName);
            Acl.require(current.acl(), principal, owner,
                method.equals("GET") ? Acl.READ_ACP : Acl.WRITE_ACP);
            if (method.equals("GET")) {
                requireEmptyBody(exchange, hash);
                sendXml(exchange, 200, Acl.xml(current.acl(), owner));
            } else {
                if (exchange.getRequestHeaders().containsKey("x-amz-acl") ||
                    exchange.getRequestHeaders().keySet().stream().anyMatch(
                        name -> name.toLowerCase(Locale.ROOT).startsWith("x-amz-grant-"))) {
                    requireEmptyBody(exchange, hash);
                    verifyContentMd5(exchange.getRequestHeaders(), new byte[0]);
                    store.setBucketAcl(bucketName, Acl.fromHeaders(exchange.getRequestHeaders(),
                        authentication.identities()));
                } else {
                    byte[] body = signedBody(exchange, hash, 65536);
                    verifyContentMd5(exchange.getRequestHeaders(), body);
                    store.setBucketAcl(bucketName, Acl.fromXml(body,
                        owner, authentication.identities()));
                }
                exchange.sendResponseHeaders(200, -1);
            }
            return;
        }
        String bucketOperation = switch (method) {
            case "PUT" -> "CreateBucket";
            case "HEAD" -> "HeadBucket";
            case "DELETE" -> "DeleteBucket";
            default -> null;
        };
        if (bucketOperation != null && (query.isEmpty() ||
            query.size() == 1 && bucketOperation.equals(query.get("x-id")))) {
            requireEmptyBody(exchange, hash);
            switch (method) {
                case "PUT" -> {
                    requireOwner(principal);
                    Map<String, String> acl = Acl.fromHeaders(exchange.getRequestHeaders(),
                        authentication.identities());
                    store.createBucket(bucketName);
                    if (!acl.isEmpty()) store.setBucketAcl(bucketName, acl);
                    exchange.getResponseHeaders().set("Location", "/" + bucketName);
                    exchange.sendResponseHeaders(200, -1);
                }
                case "HEAD" -> {
                    requireBucket(bucketName, principal, Acl.READ);
                    exchange.sendResponseHeaders(200, -1);
                }
                case "DELETE" -> {
                    requireOwner(principal);
                    if (bucketName.equals(bucket))
                        throw new StoreException(409, "BucketNotEmpty", "The configured default bucket cannot be deleted");
                    synchronized (multipart) {
                        if (!multipart.listUploads(bucketName, "").isEmpty())
                            throw new StoreException(409, "BucketNotEmpty", "Bucket has active uploads");
                        store.deleteBucket(bucketName);
                    }
                    exchange.sendResponseHeaders(204, -1);
                }
            }
            return;
        }
        store.bucket(bucketName);
        String versioningOperation = method.equals("GET") ? "GetBucketVersioning" :
            method.equals("PUT") ? "PutBucketVersioning" : null;
        if (query.containsKey("versioning") && query.get("versioning").isEmpty() &&
            (query.size() == 1 || query.size() == 2 && versioningOperation != null &&
             versioningOperation.equals(query.get("x-id")))) {
            requireOwner(principal);
            handleVersioning(exchange, hash, bucketName);
            return;
        }
        if (method.equals("GET") && query.containsKey("versions") && query.get("versions").isEmpty()) {
            requireOwner(principal);
            if (!query.keySet().stream().allMatch(java.util.Set.of("versions", "prefix", "key-marker",
                "version-id-marker", "max-keys", "encoding-type", "x-id")::contains) ||
                query.containsKey("x-id") && !"ListObjectVersions".equals(query.get("x-id")))
                unsupported("Version listing option");
            requireEmptyBody(exchange, hash);
            listVersions(exchange, query, bucketName);
            return;
        }
        if (exchange.getRequestMethod().equals("GET") && query.containsKey("uploads")) {
            requireOwner(principal);
            if (!query.get("uploads").isEmpty() ||
                !query.keySet().stream().allMatch(java.util.Set.of("uploads", "prefix", "key-marker",
                    "upload-id-marker", "max-uploads", "x-id")::contains) ||
                (query.containsKey("x-id") && !"ListMultipartUploads".equals(query.get("x-id"))))
                unsupported("Bucket operation");
            requireEmptyBody(exchange, hash);
            listUploads(exchange, query, bucketName);
            return;
        }
        if (!exchange.getRequestMethod().equals("GET") || !"2".equals(query.get("list-type")) ||
            !query.keySet().stream().allMatch(java.util.Set.of("list-type", "prefix", "delimiter", "max-keys",
                "continuation-token", "start-after", "encoding-type", "x-id")::contains) ||
            (query.containsKey("x-id") && !"ListObjectsV2".equals(query.get("x-id"))))
            unsupported("Bucket operation");
        requireBucket(bucketName, principal, Acl.READ);
        requireEmptyBody(exchange, hash);
        listObjects(exchange, query, bucketName);
    }

    private void handleObject(HttpExchange exchange, String key, Map<String, String> query,
                              SigV4.Verified verified, String bucketName) throws IOException {
        String hash = verified.payload();
        String principal = verified.principal();
        if (key.isEmpty() || key.getBytes(StandardCharsets.UTF_8).length > 1024 || key.indexOf('\0') >= 0)
            throw new StoreException(400, "InvalidArgument", "Invalid object key");
        String method = exchange.getRequestMethod();
        if (query.containsKey("acl") && query.get("acl").isEmpty() &&
            query.keySet().stream().allMatch(java.util.Set.of("acl", "versionId", "x-id")::contains) &&
            (!query.containsKey("x-id") ||
             query.get("x-id").equals(method.equals("GET") ? "GetObjectAcl" : "PutObjectAcl")) &&
            (method.equals("GET") || method.equals("PUT"))) {
            String versionId = query.get("versionId");
            try (var object = store.open(bucketName, key, versionId)) {
                var metadata = object.metadata();
                if (method.equals("GET")) {
                    Acl.require(metadata.acl(), principal, owner, Acl.READ_ACP);
                    requireEmptyBody(exchange, hash);
                    sendXml(exchange, 200, Acl.xml(metadata.acl(), owner));
                } else {
                    if (!owner.equals(principal)) {
                        if (metadata.versionId() == null || metadata.versionId().equals("null"))
                            throw new StoreException(403, "AccessDenied", "Access denied");
                        Acl.require(metadata.acl(), principal, owner, Acl.WRITE_ACP);
                    }
                    Map<String, String> acl;
                    if (exchange.getRequestHeaders().containsKey("x-amz-acl") ||
                        exchange.getRequestHeaders().keySet().stream().anyMatch(
                            name -> name.toLowerCase(Locale.ROOT).startsWith("x-amz-grant-"))) {
                        requireEmptyBody(exchange, hash);
                        verifyContentMd5(exchange.getRequestHeaders(), new byte[0]);
                        acl = Acl.fromHeaders(exchange.getRequestHeaders(), authentication.identities());
                    } else {
                        byte[] body = signedBody(exchange, hash, 65536);
                        verifyContentMd5(exchange.getRequestHeaders(), body);
                        acl = Acl.fromXml(body, owner, authentication.identities());
                    }
                    store.setObjectAcl(bucketName, key, metadata.versionId(), acl);
                    exchange.sendResponseHeaders(200, -1);
                }
            }
            return;
        }
        String taggingOperation = switch (method) {
            case "GET" -> "GetObjectTagging";
            case "PUT" -> "PutObjectTagging";
            case "DELETE" -> "DeleteObjectTagging";
            default -> null;
        };
        if (taggingOperation != null && query.containsKey("tagging") && query.get("tagging").isEmpty() &&
            query.keySet().stream().allMatch(java.util.Set.of("tagging", "versionId", "x-id")::contains) &&
            (!query.containsKey("x-id") || taggingOperation.equals(query.get("x-id")))) {
            validateObjectHeaders(exchange.getRequestHeaders());
            requireOwner(principal);
            handleTagging(exchange, method, key, hash, bucketName, query.get("versionId"));
            return;
        }
        boolean multipartRequest = multipartRequest(method, query);
        boolean versionRequest = (method.equals("GET") || method.equals("HEAD") || method.equals("DELETE")) &&
            query.containsKey("versionId") && (query.size() == 1 || query.size() == 2 && query.containsKey("x-id"));
        if (versionRequest && query.containsKey("x-id") &&
            !(method.equals("GET") ? "GetObject" : method.equals("HEAD") ? "HeadObject" : "DeleteObject")
                .equals(query.get("x-id"))) unsupported("Version query operation");
        if (!multipartRequest && !versionRequest && !query.isEmpty() && !(query.size() == 1 &&
            ("PutObject".equals(query.get("x-id")) || "CopyObject".equals(query.get("x-id")) ||
             "GetObject".equals(query.get("x-id")) ||
             "HeadObject".equals(query.get("x-id")) || "DeleteObject".equals(query.get("x-id")))))
            unsupported("Query operation");
        validateObjectHeaders(exchange.getRequestHeaders());
        if (multipartRequest) {
            if (method.equals("GET")) requireOwner(principal);
            else requireBucket(bucketName, principal, Acl.WRITE);
            handleMultipart(exchange, method, query, key, verified, bucketName);
            return;
        }
        boolean copy = exchange.getRequestHeaders().containsKey("x-amz-copy-source");
        if (!method.equals("PUT") && (copy || exchange.getRequestHeaders().containsKey("content-md5") ||
            exchange.getRequestHeaders().containsKey("x-amz-metadata-directive") ||
            exchange.getRequestHeaders().containsKey("x-amz-tagging") ||
            exchange.getRequestHeaders().containsKey("x-amz-tagging-directive") ||
            exchange.getRequestHeaders().containsKey("x-amz-sdk-checksum-algorithm") ||
            exchange.getRequestHeaders().keySet().stream().anyMatch(name ->
                name.toLowerCase(Locale.ROOT).startsWith("x-amz-checksum-") &&
                !name.equalsIgnoreCase("x-amz-checksum-mode"))))
            unsupported("Object upload header");
        if (!method.equals("PUT") || copy) requireEmptyBody(exchange, hash);
        switch (method) {
            case "PUT" -> {
                requireBucket(bucketName, principal, Acl.WRITE);
                if (copy) copyObject(exchange, key, bucketName, principal);
                else putObject(exchange, key, verified, bucketName);
            }
            case "GET", "HEAD" -> readObject(exchange, key, bucketName, query.get("versionId"), principal);
            case "DELETE" -> {
                requireBucket(bucketName, principal, Acl.WRITE);
                deleteObject(exchange, key, bucketName, query.get("versionId"));
            }
            default -> unsupported("HTTP method");
        }
    }

    private static void validateObjectHeaders(com.sun.net.httpserver.Headers headers) {
        for (String name : headers.keySet()) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("x-amz-") && !lower.startsWith("x-amz-meta-") && !java.util.Set.of("x-amz-date", "x-amz-content-sha256",
                "x-amz-sdk-checksum-algorithm", "x-amz-user-agent", "x-amz-copy-source",
                "x-amz-metadata-directive", "x-amz-tagging", "x-amz-tagging-directive",
                "x-amz-decoded-content-length", "x-amz-trailer", "x-amz-checksum-mode",
                "x-amz-acl").contains(lower) && !lower.startsWith("x-amz-checksum-") &&
                !lower.startsWith("x-amz-grant-"))
                unsupported("Amazon header");
            if (lower.startsWith("x-amz-server-side-") ||
                lower.startsWith("x-amz-copy-source-")) unsupported("Object metadata, encryption, ACL or tagging header");
        }
    }

    private void putObject(HttpExchange exchange, String key, SigV4.Verified verified,
                           String bucketName) throws IOException {
        var headers = exchange.getRequestHeaders();
        String length = SigV4.single(headers, "content-length"), condition = SigV4.single(headers, "if-none-match");
        if (condition != null && !condition.equals("*")) unsupported("Write condition");
        long bytes;
        try { bytes = length == null ? -1 : Long.parseLong(length); }
        catch (NumberFormatException e) { throw new StoreException(400, "InvalidArgument", "Invalid Content-Length"); }
        if (!verified.streaming() && headers.containsKey("content-encoding")) unsupported("Encoded payload");
        if (headers.containsKey("x-amz-metadata-directive")) unsupported("Copy metadata directive");
        if (headers.containsKey("x-amz-tagging-directive")) unsupported("Copy tagging directive");
        UploadChecksums checksums = UploadChecksums.from(headers);
        AwsChunkedInputStream chunked = verified.streaming() ? chunkedBody(exchange, verified) : null;
        if (chunked != null) bytes = decodedLength(headers);
        else if (headers.containsKey("x-amz-decoded-content-length") || headers.containsKey("x-amz-trailer"))
            unsupported("Chunked upload header");
        String type = contentType(headers);
        Map<String, String> metadata = ObjectAttributes.userMetadata(headers);
        Map<String, String> tags = ObjectAttributes.tagsHeader(SigV4.single(headers, "x-amz-tagging"));
        Map<String, String> acl = Acl.fromHeaders(headers, authentication.identities());
        if (!acl.isEmpty() && !owner.equals(verified.principal()))
            requireBucket(bucketName, verified.principal(), Acl.WRITE_ACP);
        ObjectStorage.Metadata data = store.put(bucketName, key,
            checksums.verifying(chunked == null ? exchange.getRequestBody() : chunked),
            bytes, expectedPayloadHash(verified.payload()), checksums.sha256(), condition != null, type,
            metadata, tags, () -> {
                if (chunked == null || chunked.trailerValue() == null) return checksums.metadata();
                String trailer = SigV4.single(headers, "x-amz-trailer");
                if (!checksums.metadata().isEmpty())
                    throw new StoreException(400, "InvalidRequest", "Supply one checksum algorithm");
                return Map.of(trailer, chunked.trailerValue());
            }, acl);
        exchange.getResponseHeaders().set("ETag", "\"" + data.etag() + "\"");
        if (data.versionId() != null) exchange.getResponseHeaders().set("x-amz-version-id", data.versionId());
        exchange.getResponseHeaders().set("x-amz-checksum-sha256", Base64.getEncoder().encodeToString(data.sha256()));
        data.checksums().forEach(exchange.getResponseHeaders()::set);
        exchange.sendResponseHeaders(200, -1);
    }

    private void copyObject(HttpExchange exchange, String key, String bucketName,
                            String principal) throws IOException {
        var headers = exchange.getRequestHeaders();
        if (headers.containsKey("content-encoding") || headers.containsKey("content-md5") ||
            headers.containsKey("if-none-match") || headers.containsKey("x-amz-sdk-checksum-algorithm") ||
            headers.keySet().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).startsWith("x-amz-checksum-")))
            unsupported("Copy request header");
        String source = SigV4.single(headers, "x-amz-copy-source");
        if (source == null) throw new StoreException(400, "InvalidArgument", "Missing copy source");
        if (source.startsWith("/")) source = source.substring(1);
        String sourceVersion = null;
        int question = source.indexOf('?');
        if (question >= 0) {
            Map<String, String> sourceQuery = query(source.substring(question + 1));
            if (sourceQuery.size() != 1 || !sourceQuery.containsKey("versionId"))
                throw new StoreException(400, "InvalidArgument", "Invalid copy source version");
            sourceVersion = sourceQuery.get("versionId");
            source = source.substring(0, question);
        }
        int separator = source.indexOf('/');
        if (separator <= 0 || separator == source.length() - 1)
            throw new StoreException(400, "InvalidArgument", "Invalid copy source");
        String sourceBucket = SigV4.decode(source.substring(0, separator));
        String sourceKey = SigV4.decode(source.substring(separator + 1));
        store.bucket(sourceBucket);
        if (sourceKey.isEmpty() || sourceKey.getBytes(StandardCharsets.UTF_8).length > 1024 ||
            sourceKey.indexOf('\0') >= 0)
            throw new StoreException(400, "InvalidArgument", "Invalid copy source key");
        String directive = SigV4.single(headers, "x-amz-metadata-directive");
        if (directive != null && !directive.equals("COPY") && !directive.equals("REPLACE"))
            throw new StoreException(400, "InvalidArgument", "Invalid metadata directive");
        if (!"REPLACE".equals(directive) && headers.containsKey("content-type"))
            unsupported("Content-Type requires REPLACE metadata directive");
        String tagDirective = SigV4.single(headers, "x-amz-tagging-directive");
        if (tagDirective != null && !tagDirective.equals("COPY") && !tagDirective.equals("REPLACE"))
            throw new StoreException(400, "InvalidArgument", "Invalid tagging directive");
        if (!"REPLACE".equals(tagDirective) && headers.containsKey("x-amz-tagging"))
            unsupported("Tagging header requires REPLACE tagging directive");
        if (!"REPLACE".equals(directive) && !ObjectAttributes.userMetadata(headers).isEmpty())
            unsupported("User metadata requires REPLACE metadata directive");
        try (var object = store.open(sourceBucket, sourceKey, sourceVersion)) {
            var sourceMetadata = object.metadata();
            Acl.require(sourceMetadata.acl(), principal, owner, Acl.READ);
            if (sourceMetadata.versionId() != null)
                exchange.getResponseHeaders().set("x-amz-copy-source-version-id", sourceMetadata.versionId());
            String type = "REPLACE".equals(directive) ? contentType(headers) : sourceMetadata.contentType();
            Map<String, String> metadata = "REPLACE".equals(directive)
                ? ObjectAttributes.userMetadata(headers) : sourceMetadata.userMetadata();
            Map<String, String> tags = "REPLACE".equals(tagDirective)
                ? ObjectAttributes.tagsHeader(SigV4.single(headers, "x-amz-tagging")) : sourceMetadata.tags();
            Map<String, String> acl = Acl.fromHeaders(headers, authentication.identities());
            if (!acl.isEmpty() && !owner.equals(principal))
                requireBucket(bucketName, principal, Acl.WRITE_ACP);
            var copied = store.put(bucketName, key, object.stream(), sourceMetadata.length(),
                SigV4.hex(sourceMetadata.sha256()), null, false, type, metadata, tags,
                sourceMetadata::checksums, acl);
            if (copied.versionId() != null)
                exchange.getResponseHeaders().set("x-amz-version-id", copied.versionId());
            sendXml(exchange, 200, "<CopyObjectResult><LastModified>" +
                Instant.ofEpochMilli(copied.modified()) + "</LastModified><ETag>&quot;" +
                copied.etag() + "&quot;</ETag></CopyObjectResult>");
        }
    }

    private void deleteObject(HttpExchange exchange, String key, String bucketName,
                              String versionId) throws IOException {
        if (exchange.getRequestHeaders().containsKey("if-none-match")) unsupported("Conditional delete");
        var result = store.delete(bucketName, key, versionId);
        if (result.versionId() != null)
            exchange.getResponseHeaders().set("x-amz-version-id", result.versionId());
        if (result.deleteMarker()) exchange.getResponseHeaders().set("x-amz-delete-marker", "true");
        exchange.sendResponseHeaders(204, -1);
    }

    private void handleTagging(HttpExchange exchange, String method, String key,
                               String hash, String bucketName, String versionId) throws IOException {
        if (exchange.getRequestHeaders().containsKey("x-amz-tagging") ||
            exchange.getRequestHeaders().containsKey("x-amz-tagging-directive") ||
            exchange.getRequestHeaders().containsKey("x-amz-sdk-checksum-algorithm") ||
            !method.equals("PUT") && exchange.getRequestHeaders().containsKey("content-md5") ||
            exchange.getRequestHeaders().keySet().stream().anyMatch(name ->
                name.toLowerCase(Locale.ROOT).startsWith("x-amz-checksum-")) ||
            !ObjectAttributes.userMetadata(exchange.getRequestHeaders()).isEmpty())
            unsupported("Tagging request header");
        switch (method) {
            case "GET" -> {
                requireEmptyBody(exchange, hash);
                if (versionId != null) exchange.getResponseHeaders().set("x-amz-version-id", versionId);
                sendXml(exchange, 200, tagXml(store.tags(bucketName, key, versionId)));
            }
            case "PUT" -> {
                byte[] body = signedBody(exchange, hash, 65536);
                verifyContentMd5(exchange.getRequestHeaders(), body);
                store.setTags(bucketName, key, versionId, parseTags(body));
                if (versionId != null) exchange.getResponseHeaders().set("x-amz-version-id", versionId);
                exchange.sendResponseHeaders(200, -1);
            }
            case "DELETE" -> {
                requireEmptyBody(exchange, hash);
                store.setTags(bucketName, key, versionId, Map.of());
                if (versionId != null) exchange.getResponseHeaders().set("x-amz-version-id", versionId);
                exchange.sendResponseHeaders(204, -1);
            }
            default -> unsupported("Tagging operation");
        }
    }

    private static void verifyContentMd5(com.sun.net.httpserver.Headers headers, byte[] body) {
        String encoded = SigV4.single(headers, "content-md5");
        if (encoded == null) return;
        byte[] expected;
        try { expected = Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException error) {
            throw new StoreException(400, "InvalidDigest", "Invalid Content-MD5");
        }
        if (expected.length != 16) throw new StoreException(400, "InvalidDigest", "Invalid Content-MD5");
        try {
            byte[] actual = MessageDigest.getInstance("MD5").digest(body);
            if (!MessageDigest.isEqual(expected, actual))
                throw new StoreException(400, "BadDigest", "Content-MD5 mismatch");
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static String tagXml(Map<String, String> tags) {
        StringBuilder body = new StringBuilder("<Tagging><TagSet>");
        new java.util.TreeMap<>(tags).forEach((key, value) -> body.append("<Tag><Key>")
            .append(xml(key)).append("</Key><Value>").append(xml(value)).append("</Value></Tag>"));
        return body.append("</TagSet></Tagging>").toString();
    }

    private static Map<String, String> parseTags(byte[] body) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException {
                    throw error;
                }
            });
            var document = builder.parse(new ByteArrayInputStream(body));
            var root = document.getDocumentElement();
            if (!root.getTagName().equals("Tagging")) throw new IllegalArgumentException();
            var sets = root.getElementsByTagName("TagSet");
            if (sets.getLength() != 1) throw new IllegalArgumentException();
            for (int i = 0; i < root.getChildNodes().getLength(); i++) {
                var child = root.getChildNodes().item(i);
                if (child instanceof org.w3c.dom.Element && child != sets.item(0))
                    throw new IllegalArgumentException();
            }
            Map<String, String> tags = new java.util.TreeMap<>();
            var entries = sets.item(0).getChildNodes();
            for (int i = 0; i < entries.getLength(); i++) {
                if (!(entries.item(i) instanceof org.w3c.dom.Element tag)) continue;
                if (!tag.getTagName().equals("Tag")) throw new IllegalArgumentException();
                var keys = tag.getElementsByTagName("Key");
                var values = tag.getElementsByTagName("Value");
                if (keys.getLength() != 1 || values.getLength() != 1 ||
                    tag.getElementsByTagName("*").getLength() != 2) throw new IllegalArgumentException();
                if (tags.put(keys.item(0).getTextContent(), values.item(0).getTextContent()) != null)
                    throw new IllegalArgumentException();
            }
            ObjectAttributes.validateTags(tags);
            return Map.copyOf(tags);
        } catch (Exception error) {
            throw new StoreException(400, "MalformedXML", "Invalid object tagging body");
        }
    }

    private static String contentType(com.sun.net.httpserver.Headers headers) {
        String value = SigV4.single(headers, "content-type");
        if (value == null) return "application/octet-stream";
        if (value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > 255 ||
            !value.chars().allMatch(c -> c >= 32 && c <= 126))
            throw new StoreException(400, "InvalidArgument", "Invalid Content-Type");
        return value;
    }

    private static void requireEmptyBody(HttpExchange exchange, String hash) {
        var headers = exchange.getRequestHeaders();
        if (headers.containsKey("transfer-encoding") ||
            (headers.containsKey("content-length") && !"0".equals(SigV4.single(headers, "content-length"))) ||
            !(hash.equals("UNSIGNED-PAYLOAD") || hash.equals(SigV4.hex(SigV4.hash(new byte[0])))))
            throw new StoreException(400, "InvalidRequest", "Request must have an empty body");
    }

    private static boolean multipartRequest(String method, Map<String, String> query) {
        if (query.containsKey("uploads"))
            return method.equals("POST") && query.get("uploads").isEmpty() &&
                query.keySet().stream().allMatch(java.util.Set.of("uploads", "x-id")::contains) &&
                (!query.containsKey("x-id") || query.get("x-id").equals("CreateMultipartUpload"));
        if (!query.containsKey("uploadId") ||
            !query.keySet().stream().allMatch(java.util.Set.of("uploadId", "partNumber",
                "part-number-marker", "max-parts", "x-id")::contains))
            return false;
        String xId = query.get("x-id");
        if (method.equals("PUT")) return query.containsKey("partNumber") &&
            !query.containsKey("part-number-marker") && !query.containsKey("max-parts") &&
            (xId == null || xId.equals("UploadPart"));
        if (query.containsKey("partNumber")) return false;
        if (method.equals("GET")) return xId == null || xId.equals("ListParts");
        if (query.containsKey("part-number-marker") || query.containsKey("max-parts")) return false;
        return (method.equals("POST") && (xId == null || xId.equals("CompleteMultipartUpload"))) ||
            (method.equals("DELETE") && (xId == null || xId.equals("AbortMultipartUpload")));
    }

    private void handleMultipart(HttpExchange exchange, String method, Map<String, String> query,
                                 String key, SigV4.Verified verified, String bucketName) throws IOException {
        String hash = verified.payload();
        var headers = exchange.getRequestHeaders();
        if ((headers.containsKey("content-encoding") && !(method.equals("PUT") && verified.streaming())) ||
            headers.containsKey("if-none-match"))
            unsupported("Multipart request header");
        if (headers.containsKey("x-amz-copy-source") || headers.containsKey("x-amz-metadata-directive") ||
            headers.containsKey("x-amz-tagging-directive"))
            unsupported("Multipart copy request");
        if (!query.containsKey("uploads") &&
            (headers.containsKey("x-amz-tagging") || !ObjectAttributes.userMetadata(headers).isEmpty() ||
             headers.containsKey("x-amz-acl") || headers.keySet().stream().anyMatch(
                 name -> name.toLowerCase(Locale.ROOT).startsWith("x-amz-grant-"))))
            unsupported("Multipart object attributes belong on initiation");
        if (!method.equals("PUT") && (headers.containsKey("content-md5") ||
            headers.containsKey("x-amz-sdk-checksum-algorithm") ||
            headers.keySet().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).startsWith("x-amz-checksum-"))))
            unsupported("Multipart checksum header");
        if (query.containsKey("uploads")) {
            requireEmptyBody(exchange, hash);
            Map<String, String> acl = Acl.fromHeaders(headers, authentication.identities());
            if (!acl.isEmpty() && !owner.equals(verified.principal()))
                requireBucket(bucketName, verified.principal(), Acl.WRITE_ACP);
            String id = multipart.create(bucketName, key, contentType(headers),
                ObjectAttributes.userMetadata(headers),
                ObjectAttributes.tagsHeader(SigV4.single(headers, "x-amz-tagging")), acl);
            sendXml(exchange, 200, "<InitiateMultipartUploadResult><Bucket>" + xml(bucketName) +
                "</Bucket><Key>" + xml(key) + "</Key><UploadId>" + id +
                "</UploadId></InitiateMultipartUploadResult>");
            return;
        }
        String id = query.get("uploadId");
        switch (method) {
            case "PUT" -> {
                int number;
                try { number = Integer.parseInt(query.get("partNumber")); }
                catch (NumberFormatException e) { throw new StoreException(400, "InvalidArgument", "Invalid part number"); }
                long length = verified.streaming() ? decodedLength(headers) : contentLength(headers);
                UploadChecksums checksums = UploadChecksums.from(headers);
                AwsChunkedInputStream chunked = verified.streaming() ? chunkedBody(exchange, verified) : null;
                String etag = multipart.putPart(id, bucketName, key, number,
                    checksums.verifying(chunked == null ? exchange.getRequestBody() : chunked),
                    length, expectedPayloadHash(hash), checksums.sha256());
                exchange.getResponseHeaders().set("ETag", "\"" + etag + "\"");
                checksums.response(exchange.getResponseHeaders());
                if (chunked != null && chunked.trailerValue() != null)
                    exchange.getResponseHeaders().set(SigV4.single(headers, "x-amz-trailer"), chunked.trailerValue());
                exchange.sendResponseHeaders(200, -1);
            }
            case "POST" -> {
                byte[] body = signedBody(exchange, hash, 65536);
                List<MultipartStorage.Part> parts = completedParts(body);
                var meta = multipart.complete(id, bucketName, key, parts);
                if (meta.versionId() != null)
                    exchange.getResponseHeaders().set("x-amz-version-id", meta.versionId());
                sendXml(exchange, 200, "<CompleteMultipartUploadResult><Bucket>" + xml(bucketName) +
                    "</Bucket><Key>" + xml(key) + "</Key><ETag>&quot;" + meta.etag() +
                    "&quot;</ETag></CompleteMultipartUploadResult>");
            }
            case "DELETE" -> {
                requireEmptyBody(exchange, hash);
                multipart.abort(id, bucketName, key);
                exchange.sendResponseHeaders(204, -1);
            }
            case "GET" -> {
                requireEmptyBody(exchange, hash);
                listParts(exchange, id, key, query, bucketName);
            }
            default -> unsupported("Multipart operation");
        }
    }

    private void listParts(HttpExchange exchange, String id, String key, Map<String, String> query,
                           String bucketName) throws IOException {
        int marker = boundedNumber(query.get("part-number-marker"), 0, 10000, 0);
        int maxParts = boundedNumber(query.get("max-parts"), 1, 1000, 1000);
        MultipartStorage.PartPage page = multipart.listParts(id, bucketName, key, marker, maxParts);
        StringBuilder body = new StringBuilder("<ListPartsResult><Bucket>").append(xml(bucketName))
            .append("</Bucket><Key>").append(xml(key)).append("</Key><UploadId>").append(xml(id))
            .append("</UploadId><PartNumberMarker>").append(marker)
            .append("</PartNumberMarker><NextPartNumberMarker>").append(page.nextMarker())
            .append("</NextPartNumberMarker><MaxParts>").append(maxParts)
            .append("</MaxParts><IsTruncated>").append(page.truncated()).append("</IsTruncated>");
        for (MultipartStorage.PartInfo part : page.parts()) {
            body.append("<Part><PartNumber>").append(part.number()).append("</PartNumber><LastModified>")
                .append(Instant.ofEpochMilli(part.modified())).append("</LastModified><ETag>&quot;")
                .append(part.etag()).append("&quot;</ETag><Size>").append(part.length()).append("</Size></Part>");
        }
        sendXml(exchange, 200, body.append("</ListPartsResult>").toString());
    }

    private void listUploads(HttpExchange exchange, Map<String, String> query, String bucketName) throws IOException {
        String prefix = query.getOrDefault("prefix", "");
        String marker = query.getOrDefault("key-marker", "");
        String uploadMarker = query.getOrDefault("upload-id-marker", "");
        if (!uploadMarker.isEmpty() && marker.isEmpty())
            throw new StoreException(400, "InvalidArgument", "Upload ID marker requires a key marker");
        int maximum = boundedNumber(query.get("max-uploads"), 1, 1000, 1000);
        List<MultipartStorage.UploadInfo> uploads = multipart.listUploads(bucketName, prefix);
        List<MultipartStorage.UploadInfo> page = new ArrayList<>();
        boolean truncated = false;
        for (MultipartStorage.UploadInfo upload : uploads) {
            if (upload.key().compareTo(marker) < 0 ||
                (upload.key().equals(marker) && upload.id().compareTo(uploadMarker) <= 0)) continue;
            if (page.size() == maximum) {
                truncated = true;
                break;
            }
            page.add(upload);
        }
        StringBuilder body = new StringBuilder("<ListMultipartUploadsResult><Bucket>").append(xml(bucketName))
            .append("</Bucket><KeyMarker>").append(xml(marker)).append("</KeyMarker><UploadIdMarker>")
            .append(xml(uploadMarker)).append("</UploadIdMarker><MaxUploads>").append(maximum)
            .append("</MaxUploads><IsTruncated>").append(truncated).append("</IsTruncated>");
        if (truncated) {
            MultipartStorage.UploadInfo last = page.getLast();
            body.append("<NextKeyMarker>").append(xml(last.key())).append("</NextKeyMarker><NextUploadIdMarker>")
                .append(xml(last.id())).append("</NextUploadIdMarker>");
        }
        for (MultipartStorage.UploadInfo upload : page) {
            body.append("<Upload><Key>").append(xml(upload.key())).append("</Key><UploadId>")
                .append(xml(upload.id())).append("</UploadId><Initiated>")
                .append(Instant.ofEpochMilli(upload.created())).append("</Initiated></Upload>");
        }
        sendXml(exchange, 200, body.append("</ListMultipartUploadsResult>").toString());
    }

    private static int boundedNumber(String text, int minimum, int maximum, int defaultValue) {
        if (text == null) return defaultValue;
        int value;
        try {
            value = Integer.parseInt(text);
        } catch (NumberFormatException error) {
            throw new StoreException(400, "InvalidArgument", "Invalid listing limit or marker");
        }
        if (value < minimum || value > maximum)
            throw new StoreException(400, "InvalidArgument", "Invalid listing limit or marker");
        return value;
    }

    private static long decodedLength(com.sun.net.httpserver.Headers headers) {
        String value = SigV4.single(headers, "x-amz-decoded-content-length");
        if (value == null || !value.matches("[0-9]{1,19}"))
            throw new StoreException(400, "InvalidArgument", "Invalid decoded content length");
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) {
            throw new StoreException(400, "InvalidArgument", "Invalid decoded content length");
        }
    }

    private static AwsChunkedInputStream chunkedBody(HttpExchange exchange,
                                                      SigV4.Verified verified) {
        var headers = exchange.getRequestHeaders();
        if (!"aws-chunked".equals(SigV4.single(headers, "content-encoding")))
            throw new StoreException(400, "InvalidRequest", "Signed chunk upload requires aws-chunked encoding");
        if (contentLength(headers) < 0 && !headers.containsKey("transfer-encoding"))
            throw new StoreException(411, "MissingContentLength", "Encoded content length is required");
        long length = decodedLength(headers);
        String trailer = SigV4.single(headers, "x-amz-trailer");
        if (verified.payload().endsWith("-TRAILER")) {
            if (trailer == null || !trailer.matches("x-amz-checksum-[a-z0-9]+"))
                throw new StoreException(400, "InvalidRequest", "A checksum trailer is required");
        } else if (trailer != null) {
            throw new StoreException(400, "InvalidRequest", "Unexpected checksum trailer");
        }
        return new AwsChunkedInputStream(exchange.getRequestBody(), verified, length, trailer);
    }

    private static long contentLength(com.sun.net.httpserver.Headers headers) {
        String text = SigV4.single(headers, "content-length");
        if (text == null) return -1;
        try { return Long.parseLong(text); }
        catch (NumberFormatException e) { throw new StoreException(400, "InvalidArgument", "Invalid Content-Length"); }
    }
    private static String expectedPayloadHash(String hash) {
        return hash.matches("[0-9a-f]{64}") ? hash : null;
    }
    private static byte[] signedBody(HttpExchange exchange, String hash, int limit) throws IOException {
        if (!hash.equals("UNSIGNED-PAYLOAD") && !hash.matches("[0-9a-f]{64}"))
            throw new StoreException(400, "InvalidRequest", "Unsupported body signing mode");
        long length = contentLength(exchange.getRequestHeaders());
        if (length < 0) throw new StoreException(411, "MissingContentLength", "Content-Length is required");
        if (length > limit) throw new StoreException(413, "EntityTooLarge", "Request body is too large");
        byte[] body = exchange.getRequestBody().readNBytes(limit + 1);
        if (body.length != length) throw new StoreException(400, "IncompleteBody", "Body length does not match Content-Length");
        if (!hash.equals("UNSIGNED-PAYLOAD") && !SigV4.hex(SigV4.hash(body)).equals(hash))
            throw new StoreException(400, "XAmzContentSHA256Mismatch", "Payload hash mismatch");
        return body;
    }
    private static List<MultipartStorage.Part> completedParts(byte[] body) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            if (!document.getDocumentElement().getNodeName().equals("CompleteMultipartUpload"))
                throw new IllegalArgumentException();
            var nodes = document.getDocumentElement().getChildNodes();
            List<MultipartStorage.Part> parts = new ArrayList<>();
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof org.w3c.dom.Element element)) continue;
                if (!element.getTagName().equals("Part")) throw new IllegalArgumentException();
                String number = null, etag = null;
                var fields = element.getChildNodes();
                for (int j = 0; j < fields.getLength(); j++) {
                    if (!(fields.item(j) instanceof org.w3c.dom.Element field)) continue;
                    if (field.getTagName().equals("PartNumber")) number = field.getTextContent().trim();
                    else if (field.getTagName().equals("ETag")) etag = field.getTextContent().trim();
                    else throw new IllegalArgumentException();
                }
                if (number == null || etag == null || !etag.matches("\"?[0-9a-f]{32}\"?"))
                    throw new IllegalArgumentException();
                parts.add(new MultipartStorage.Part(Integer.parseInt(number), etag));
                if (parts.size() > 10000) throw new IllegalArgumentException();
            }
            return parts;
        } catch (Exception error) {
            throw new StoreException(400, "MalformedXML", "Invalid multipart completion body");
        }
    }
    private static void sendXml(HttpExchange exchange, int status, String xml) throws IOException {
        byte[] body = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + xml).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private void readObject(HttpExchange exchange, String key, String bucketName,
                            String versionId, String principal) throws IOException {
        var headers = exchange.getRequestHeaders();
        String checksumMode = SigV4.single(headers, "x-amz-checksum-mode");
        if (checksumMode != null && !checksumMode.equals("ENABLED"))
            throw new StoreException(400, "InvalidArgument", "Invalid checksum mode");
        if (headers.containsKey("if-match") || headers.containsKey("if-modified-since") ||
            headers.containsKey("if-unmodified-since")) unsupported("Conditional read");
        try (var object = store.open(bucketName, key, versionId)) {
            var meta = object.metadata();
            Acl.require(meta.acl(), principal, owner, Acl.READ);
            String etag = "\"" + meta.etag() + "\"";
            String noneMatch = SigV4.single(headers, "if-none-match");
            if (noneMatch != null && (noneMatch.equals("*") ||
                java.util.Arrays.stream(noneMatch.split(",")).map(String::trim).anyMatch(etag::equals))) {
                exchange.getResponseHeaders().set("ETag", etag);
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            Range range;
            try { range = range(SigV4.single(headers, "range"), meta.length()); }
            catch (StoreException error) {
                if (error.status == 416) exchange.getResponseHeaders().set("Content-Range", "bytes */" + meta.length());
                throw error;
            }
            var response = exchange.getResponseHeaders();
            response.set("Content-Type", meta.contentType());
            response.set("Content-Length", Long.toString(range.length()));
            response.set("Accept-Ranges", "bytes");
            response.set("ETag", etag);
            if (meta.versionId() != null) response.set("x-amz-version-id", meta.versionId());
            response.set("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(meta.modified())));
            meta.userMetadata().forEach((name, value) -> response.set("x-amz-meta-" + name, value));
            if (checksumMode != null)
                meta.checksums().forEach(response::set);
            if (!meta.tags().isEmpty()) response.set("x-amz-tagging-count", Integer.toString(meta.tags().size()));
            if (range.partial()) response.set("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + meta.length());
            int status = range.partial() ? 206 : 200;
            if (exchange.getRequestMethod().equals("HEAD") || range.length() == 0)
                exchange.sendResponseHeaders(status, -1);
            else {
                object.stream().skipNBytes(range.start());
                exchange.sendResponseHeaders(status, range.length());
                byte[] buffer = new byte[65536];
                long left = range.length();
                while (left > 0) {
                    int n = object.stream().read(buffer, 0, (int) Math.min(buffer.length, left));
                    if (n < 0) throw new IOException("Object body ended before its recorded length");
                    exchange.getResponseBody().write(buffer, 0, n);
                    left -= n;
                }
            }
        }
    }

    private record Range(long start, long end, boolean partial) {
        long length() { return end < start ? 0 : end - start + 1; }
    }
    private static Range range(String header, long size) {
        if (header == null) return new Range(0, size - 1, false);
        if (!header.matches("bytes=[0-9]*-[0-9]*") || header.equals("bytes=-") || size == 0)
            throw new StoreException(416, "InvalidRange", "The requested range is not satisfiable");
        String[] parts = header.substring(6).split("-", -1);
        try {
            long start, end;
            if (parts[0].isEmpty()) {
                long suffix = Long.parseLong(parts[1]);
                if (suffix == 0) throw new NumberFormatException();
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(parts[0]);
                end = parts[1].isEmpty() ? size - 1 : Math.min(Long.parseLong(parts[1]), size - 1);
            }
            if (start >= size || end < start) throw new NumberFormatException();
            return new Range(start, end, true);
        } catch (NumberFormatException e) {
            throw new StoreException(416, "InvalidRange", "The requested range is not satisfiable");
        }
    }

    private void handleVersioning(HttpExchange exchange, String hash, String bucketName) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                requireEmptyBody(exchange, hash);
                var state = store.bucket(bucketName).versioning();
                String status = state == ObjectStorage.VersioningState.NEVER ? "" :
                    "<Status>" + state.name().charAt(0) + state.name().substring(1).toLowerCase(Locale.ROOT) +
                    "</Status>";
                sendXml(exchange, 200, "<VersioningConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                    status + "</VersioningConfiguration>");
            }
            case "PUT" -> {
                byte[] body = signedBody(exchange, hash, 65536);
                verifyContentMd5(exchange.getRequestHeaders(), body);
                store.setVersioning(bucketName, parseVersioning(body));
                exchange.sendResponseHeaders(200, -1);
            }
            default -> unsupported("Bucket versioning operation");
        }
    }

    private static ObjectStorage.VersioningState parseVersioning(byte[] body) {
        try {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
            var root = document.getDocumentElement();
            if (!root.getNodeName().equals("VersioningConfiguration")) throw new IllegalArgumentException();
            String status = null;
            for (int i = 0; i < root.getChildNodes().getLength(); i++) {
                var child = root.getChildNodes().item(i);
                if (!(child instanceof org.w3c.dom.Element element)) continue;
                if (!element.getNodeName().equals("Status") || status != null)
                    throw new IllegalArgumentException();
                status = element.getTextContent().trim();
            }
            if ("Enabled".equals(status)) return ObjectStorage.VersioningState.ENABLED;
            if ("Suspended".equals(status)) return ObjectStorage.VersioningState.SUSPENDED;
        } catch (Exception ignored) { }
        throw new StoreException(400, "MalformedXML", "Invalid versioning configuration");
    }

    private void listVersions(HttpExchange exchange, Map<String, String> query,
                              String bucketName) throws IOException {
        String encoding = query.get("encoding-type");
        if (encoding != null && !encoding.equals("url")) unsupported("Encoding type");
        int maximum = boundedNumber(query.get("max-keys"), 0, 1000, 1000);
        String keyMarker = query.get("key-marker");
        String versionMarker = query.get("version-id-marker");
        if (versionMarker != null && keyMarker == null)
            throw new StoreException(400, "InvalidArgument", "Version ID marker requires a key marker");
        var page = store.listVersions(bucketName, query.getOrDefault("prefix", ""),
            keyMarker, versionMarker, maximum);
        StringBuilder body = new StringBuilder("<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>")
            .append(xml(bucketName)).append("</Name><Prefix>")
            .append(xml(listKey(query.getOrDefault("prefix", ""), encoding))).append("</Prefix><KeyMarker>")
            .append(xml(listKey(query.getOrDefault("key-marker", ""), encoding)))
            .append("</KeyMarker><VersionIdMarker>")
            .append(xml(query.getOrDefault("version-id-marker", "")))
            .append("</VersionIdMarker><MaxKeys>").append(maximum)
            .append("</MaxKeys><IsTruncated>").append(page.truncated()).append("</IsTruncated>");
        if (encoding != null) body.append("<EncodingType>url</EncodingType>");
        if (page.truncated()) body.append("<NextKeyMarker>")
            .append(xml(listKey(page.nextKey(), encoding))).append("</NextKeyMarker><NextVersionIdMarker>")
            .append(xml(page.nextVersionId())).append("</NextVersionIdMarker>");
        for (var entry : page.entries()) {
            body.append(entry.deleteMarker() ? "<DeleteMarker>" : "<Version>")
                .append("<Key>").append(xml(listKey(entry.key(), encoding)))
                .append("</Key><VersionId>").append(xml(entry.versionId()))
                .append("</VersionId><IsLatest>").append(entry.latest())
                .append("</IsLatest><LastModified>")
                .append(Instant.ofEpochMilli(entry.modified())).append("</LastModified>");
            if (!entry.deleteMarker()) body.append("<ETag>&quot;").append(entry.metadata().etag())
                .append("&quot;</ETag><Size>").append(entry.metadata().length()).append("</Size>");
            body.append("<Owner><ID>objectstore</ID><DisplayName>ObjectStore</DisplayName></Owner>")
                .append(entry.deleteMarker() ? "</DeleteMarker>" : "<StorageClass>STANDARD</StorageClass></Version>");
        }
        sendXml(exchange, 200, body.append("</ListVersionsResult>").toString());
    }

    private void listObjects(HttpExchange exchange, Map<String, String> query, String bucketName) throws IOException {
        ListRequest request = listRequest(query);
        var page = store.list(bucketName, request.prefix(), request.delimiter(), request.maxKeys(), request.after());
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
        appendListHeader(xml, query, request, page, bucketName);
        appendListEntries(xml, page, request.encoding());
        if (page.truncated()) xml.append("<NextContinuationToken>")
            .append(Base64.getUrlEncoder().withoutPadding().encodeToString(page.nextKey().getBytes(StandardCharsets.UTF_8)))
            .append("</NextContinuationToken>");
        xml.append("</ListBucketResult>");
        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }

    private record ListRequest(String prefix, String delimiter, String encoding, int maxKeys, String after) { }

    private static ListRequest listRequest(Map<String, String> query) {
        String prefix = query.getOrDefault("prefix", ""), delimiter = query.getOrDefault("delimiter", "");
        String encoding = query.get("encoding-type");
        if (encoding != null && !encoding.equals("url")) unsupported("Encoding type");
        if (query.containsKey("continuation-token") && query.containsKey("start-after"))
            throw new StoreException(400, "InvalidArgument", "Use either continuation-token or start-after");
        int maxKeys;
        try { maxKeys = Integer.parseInt(query.getOrDefault("max-keys", "1000")); }
        catch (NumberFormatException e) { throw new StoreException(400, "InvalidArgument", "Invalid max-keys"); }
        if (maxKeys < 0 || maxKeys > 1000) throw new StoreException(400, "InvalidArgument", "Invalid max-keys");
        String after = query.get("start-after");
        if (query.containsKey("continuation-token")) {
            try {
                byte[] decoded = Base64.getUrlDecoder().decode(query.get("continuation-token"));
                after = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(decoded)).toString();
            } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException e) {
                throw new StoreException(400, "InvalidArgument", "Invalid continuation token");
            }
        }
        return new ListRequest(prefix, delimiter, encoding, maxKeys, after);
    }

    private void appendListHeader(StringBuilder xml, Map<String, String> query, ListRequest request,
                                  ObjectStorage.ListPage page, String bucketName) {
        String prefix = request.prefix(), delimiter = request.delimiter(), encoding = request.encoding();
        xml.append("<Name>").append(xml(bucketName)).append("</Name><Prefix>").append(xml(listKey(prefix, encoding))).append("</Prefix>");
        if (!delimiter.isEmpty()) xml.append("<Delimiter>").append(xml(listKey(delimiter, encoding))).append("</Delimiter>");
        if (encoding != null) xml.append("<EncodingType>url</EncodingType>");
        if (query.containsKey("continuation-token")) xml.append("<ContinuationToken>")
            .append(xml(query.get("continuation-token"))).append("</ContinuationToken>");
        if (query.containsKey("start-after")) xml.append("<StartAfter>")
            .append(xml(listKey(query.get("start-after"), encoding))).append("</StartAfter>");
        xml.append("<KeyCount>").append(page.keyCount()).append("</KeyCount><MaxKeys>").append(request.maxKeys())
            .append("</MaxKeys><IsTruncated>").append(page.truncated()).append("</IsTruncated>");
    }

    private static void appendListEntries(StringBuilder xml, ObjectStorage.ListPage page, String encoding) {
        int objectAt = 0, prefixAt = 0;
        while (objectAt < page.objects().size() || prefixAt < page.prefixes().size()) {
            if (objectAt < page.objects().size() &&
                (prefixAt == page.prefixes().size() ||
                 page.objects().get(objectAt).key().compareTo(page.prefixes().get(prefixAt)) < 0)) {
                var entry = page.objects().get(objectAt++);
                var meta = entry.metadata();
                xml.append("<Contents><Key>").append(xml(listKey(entry.key(), encoding))).append("</Key><LastModified>")
                    .append(Instant.ofEpochMilli(meta.modified())).append("</LastModified><ETag>&quot;")
                    .append(meta.etag()).append("&quot;</ETag><Size>").append(meta.length())
                    .append("</Size><StorageClass>STANDARD</StorageClass></Contents>");
            } else {
                xml.append("<CommonPrefixes><Prefix>")
                    .append(xml(listKey(page.prefixes().get(prefixAt++), encoding)))
                    .append("</Prefix></CommonPrefixes>");
            }
        }
    }

    private static String listKey(String key, String encoding) {
        return encoding == null ? key : SigV4.encode(key, false);
    }
    private static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isEmpty()) return result;
        for (String part : raw.split("&", -1)) {
            String[] pair = part.split("=", 2);
            String name = SigV4.decode(pair[0]);
            String value = SigV4.decode(pair.length == 2 ? pair[1] : "");
            if (result.put(name, value) != null)
                throw new StoreException(400, "InvalidArgument", "Duplicate query parameter");
        }
        return result;
    }
    private static void unsupported(String feature) { throw new StoreException(501, "NotImplemented", feature + " is not supported"); }
    private static String xml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
    private static void sendError(HttpExchange exchange, int status, String code, String message, String id) throws IOException {
        if (exchange.getResponseCode() != -1) return;
        byte[] body = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>" + xml(code) +
            "</Code><Message>" + xml(message) + "</Message><RequestId>" + id + "</RequestId></Error>")
            .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(status, exchange.getRequestMethod().equals("HEAD") ? -1 : body.length);
        if (!exchange.getRequestMethod().equals("HEAD")) exchange.getResponseBody().write(body);
    }
    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        String access = required(env, "S3_ACCESS_KEY"), secret = required(env, "S3_SECRET_KEY");
        String bucket = env.getOrDefault("S3_BUCKET", "lunaris-files"), region = env.getOrDefault("S3_REGION", "us-east-1");
        if (!access.matches("[A-Za-z0-9]{16,128}") || secret.length() < 32 ||
            !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))
            throw new IllegalArgumentException("Invalid storage credentials/bucket configuration");
        long maxObject = Long.parseLong(env.getOrDefault("MAX_OBJECT_BYTES", "134217728"));
        long maxTotal = Long.parseLong(env.getOrDefault("MAX_TOTAL_BYTES", "2147483648"));
        if (maxObject < 1 || maxObject > 1073741824L || maxTotal < maxObject)
            throw new IllegalArgumentException("Invalid size limits");
        String mode = env.getOrDefault("STORE_MODE", "disk");
        ObjectStorage store;
        MultipartStorage multipart;
        if (mode.equals("cluster")) {
            if (!"true".equals(env.get("CLUSTER_LOCAL_DEV")))
                throw new IllegalArgumentException("Cluster mode is local development only; set CLUSTER_LOCAL_DEV=true");
            String[] urls = required(env, "CLUSTER_NODES").split(",", -1);
            if (urls.length < 2) throw new IllegalArgumentException("CLUSTER_NODES requires at least two URLs");
            store = new ClusterStore(required(env, "POSTGRES_JDBC_URL"), required(env, "POSTGRES_USER"),
                required(env, "POSTGRES_PASSWORD"), bucket,
                java.util.Arrays.stream(urls).map(URI::create).toList(), required(env, "CLUSTER_TOKEN"), null,
                maxObject, maxTotal, "true".equals(env.get("CLUSTER_TEST_NODE_DOMAINS")));
            multipart = (ClusterStore) store;
        } else if (mode.equals("disk")) {
            DiskStore disk = new DiskStore(Path.of(env.getOrDefault("DATA_DIR", "/data")), maxObject, maxTotal);
            store = disk;
            multipart = new MultipartStore(disk);
        } else throw new IllegalArgumentException("Invalid STORE_MODE");
        var app = new Main(store, multipart, new SigV4(identities(env, access, secret),
            access, region, Clock.systemUTC()), bucket, ClientLimits.fromEnvironment(env));
        int port = Integer.parseInt(env.getOrDefault("PORT", "9000"));
        var server = HttpServer.create(mode.equals("cluster")
            ? new InetSocketAddress(env.getOrDefault("BIND_ADDRESS", "127.0.0.1"), port)
            : new InetSocketAddress(port), 64);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", app::handle);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(5);
            executor.close();
            try { store.close(); }
            catch (IOException error) { System.err.println("Could not release ObjectStore data lock: " + error.getMessage()); }
        }));
        server.start();
        if (store instanceof DiskStore disk) printStartup(disk, app.multipart, bucket, region, port);
        else System.out.println("ObjectStore local cluster prototype v" + Version.VALUE + " listening on :" + port);
    }

    static Map<String, String> identities(Map<String, String> env, String access, String secret)
        throws IOException {
        Map<String, String> values = new HashMap<>();
        values.put(access, secret);
        String file = env.get("S3_CREDENTIALS_FILE");
        if (file == null || file.isBlank()) return values;
        List<String> lines = java.nio.file.Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
        if (lines.size() > 63) throw new IllegalArgumentException("Too many additional identities");
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int separator = line.indexOf(':');
            if (separator < 0) throw new IllegalArgumentException("Invalid additional credential entry");
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!key.matches("[A-Za-z0-9]{16,128}") || value.length() < 32 ||
                values.putIfAbsent(key, value) != null)
                throw new IllegalArgumentException("Invalid or duplicate additional credential");
        }
        return values;
    }
    private static void printStartup(DiskStore store, MultipartStorage multipart,
                                     String bucket, String region, int port) {
        System.out.println("       *");
        System.out.println("      / \\       LUNARSKY");
        System.out.println("     / L \\      ObjectStore");
        System.out.println("    /_____\\     v" + Version.VALUE);
        System.out.println();
        System.out.println("  Bucket     " + bucket + " (" + region + ")");
        System.out.println("  Objects    " + store.objectCount() + " total, " +
            store.indexedObjects() + " indexed, " + size(store.usedBytes()) + " / " + size(store.maxTotal()));
        if (store.legacyObjects() > 0)
            System.out.println("  Legacy     " + store.legacyObjects() + " objects without stored keys");
        System.out.println("  Multipart  " + multipart.activeUploads() + " active, " +
            size(multipart.stagedBytes()) + " staged");
        System.out.println("  Listening  :" + port);
    }
    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
