package cloud.lunarsky.store;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

final class ObjectHandler {
    private final ObjectStorage store;
    private final MultipartHandler multipartHandler;
    private final SigV4 authentication;
    private final S3Access access;
    private final String owner;

    ObjectHandler(ObjectStorage store, MultipartHandler multipartHandler,
                  SigV4 authentication, S3Access access) {
        this.store = store;
        this.multipartHandler = multipartHandler;
        this.authentication = authentication;
        this.access = access;
        this.owner = access.owner();
    }

    void handleObject(HttpExchange exchange, String key, Map<String, String> query,
                              SigV4.Verified verified, String bucketName) throws IOException {
        String hash = verified.payload();
        String principal = verified.principal();
        if (key.isEmpty() || key.getBytes(StandardCharsets.UTF_8).length > 1024 || key.indexOf('\0') >= 0)
            throw new StoreException(400, "InvalidArgument", "Invalid object key");
        String method = exchange.getRequestMethod();
        if (isAclRequest(method, query)) {
            handleAcl(exchange, key, hash, principal, bucketName, query.get("versionId"));
            return;
        }
        if (isTaggingRequest(method, query)) {
            S3Request.validateObjectHeaders(exchange.getRequestHeaders());
            access.requireOwner(principal);
            handleTagging(exchange, method, key, hash, bucketName, query.get("versionId"));
            return;
        }
        boolean multipartRequest = S3Request.multipartRequest(method, query);
        boolean copy = exchange.getRequestHeaders().containsKey("x-amz-copy-source");
        validateObjectQuery(exchange, query, multipartRequest, copy);
        S3Request.validateObjectHeaders(exchange.getRequestHeaders());
        if (multipartRequest) {
            if (method.equals("GET")) access.requireOwner(principal);
            else access.requireWrite(bucketName, key, principal,
                method.equals("DELETE") ? "s3:AbortMultipartUpload" : "s3:PutObject");
            multipartHandler.handleMultipart(exchange, method, query, key, verified, bucketName);
            return;
        }
        validateUploadHeaders(exchange, copy);
        if (!method.equals("PUT") || copy) S3Request.requireEmptyBody(exchange, hash);
        handleObjectMethod(exchange, key, query, verified, bucketName, copy);
    }

    private static boolean isAclRequest(String method, Map<String, String> query) {
        return query.containsKey("acl") && query.get("acl").isEmpty() &&
            query.keySet().stream().allMatch(java.util.Set.of("acl", "versionId", "x-id")::contains) &&
            (!query.containsKey("x-id") ||
             query.get("x-id").equals(method.equals("GET") ? "GetObjectAcl" : "PutObjectAcl")) &&
            (method.equals("GET") || method.equals("PUT"));
    }

    private void handleAcl(HttpExchange exchange, String key, String hash, String principal,
                           String bucketName, String versionId) throws IOException {
        try (var object = store.open(bucketName, key, versionId)) {
            var metadata = object.metadata();
            if (exchange.getRequestMethod().equals("GET")) {
                access.requireObject(bucketName, key, metadata, principal, Acl.READ_ACP,
                    versionId == null ? "s3:GetObjectAcl" : "s3:GetObjectVersionAcl");
                S3Request.requireEmptyBody(exchange, hash);
                S3Xml.sendXml(exchange, 200, Acl.xml(
                    store.bucket(bucketName).ownership() == ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED
                        ? Map.of() : metadata.acl(), owner));
            } else {
                putAcl(exchange, key, hash, principal, bucketName, versionId, metadata);
            }
        }
    }

    private void putAcl(HttpExchange exchange, String key, String hash, String principal,
                        String bucketName, String versionId, ObjectStorage.Metadata metadata) throws IOException {
        if (store.bucket(bucketName).ownership() == ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED)
            throw new StoreException(400, "AccessControlListNotSupported", "ACLs are disabled for this bucket");
        if (!owner.equals(principal) &&
            (metadata.versionId() == null || metadata.versionId().equals("null")))
            throw new StoreException(403, "AccessDenied", "Access denied");
        access.requireObject(bucketName, key, metadata, principal, Acl.WRITE_ACP,
            versionId == null ? "s3:PutObjectAcl" : "s3:PutObjectVersionAcl");
        Map<String, String> acl = requestAcl(exchange, hash);
        BucketSecurity.checkAcl(store.bucket(bucketName), exchange.getRequestHeaders(), acl, true);
        store.setObjectAcl(bucketName, key, metadata.versionId(), acl);
        exchange.sendResponseHeaders(200, -1);
    }

    private Map<String, String> requestAcl(HttpExchange exchange, String hash) throws IOException {
        var headers = exchange.getRequestHeaders();
        if (headers.containsKey("x-amz-acl") || headers.keySet().stream().anyMatch(
            name -> name.toLowerCase(Locale.ROOT).startsWith("x-amz-grant-"))) {
            S3Request.requireEmptyBody(exchange, hash);
            S3Request.verifyContentMd5(headers, new byte[0]);
            return Acl.fromHeaders(headers, authentication.identities());
        }
        byte[] body = S3Request.signedBody(exchange, hash, 65536);
        S3Request.verifyContentMd5(headers, body);
        return Acl.fromXml(body, owner, authentication.identities());
    }

    private static boolean isTaggingRequest(String method, Map<String, String> query) {
        String taggingOperation = switch (method) {
            case "GET" -> "GetObjectTagging";
            case "PUT" -> "PutObjectTagging";
            case "DELETE" -> "DeleteObjectTagging";
            default -> null;
        };
        return taggingOperation != null && query.containsKey("tagging") && query.get("tagging").isEmpty() &&
            query.keySet().stream().allMatch(java.util.Set.of("tagging", "versionId", "x-id")::contains) &&
            (!query.containsKey("x-id") || taggingOperation.equals(query.get("x-id")));
    }

    private static void validateObjectQuery(HttpExchange exchange, Map<String, String> query,
                                            boolean multipartRequest, boolean copy) {
        String method = exchange.getRequestMethod();
        if (query.containsKey("x-amz-acl")) {
            if (!method.equals("PUT") || copy || !"private".equals(query.get("x-amz-acl")))
                throw new StoreException(400, "InvalidArgument", "Unsupported ACL query value");
            if (exchange.getRequestHeaders().containsKey("x-amz-acl"))
                throw new StoreException(400, "InvalidRequest", "Supply ACL once");
        }
        boolean versionRequest = isVersionRequest(method, query);
        boolean directUploadQuery = isDirectUploadQuery(method, query, copy);
        if (!multipartRequest && !versionRequest && !query.isEmpty() && !directUploadQuery &&
            !isObjectOperationQuery(query))
            S3Request.unsupported("Query operation");
    }

    private static boolean isVersionRequest(String method, Map<String, String> query) {
        boolean versionRequest = (method.equals("GET") || method.equals("HEAD") || method.equals("DELETE")) &&
            query.containsKey("versionId") && (query.size() == 1 || query.size() == 2 && query.containsKey("x-id"));
        if (versionRequest && query.containsKey("x-id") &&
            !(method.equals("GET") ? "GetObject" : method.equals("HEAD") ? "HeadObject" : "DeleteObject")
                .equals(query.get("x-id"))) S3Request.unsupported("Version query operation");
        return versionRequest;
    }

    private static boolean isDirectUploadQuery(String method, Map<String, String> query, boolean copy) {
        return method.equals("PUT") && !copy &&
            (query.get("x-id") == null || query.get("x-id").equals("PutObject")) &&
            query.keySet().stream().allMatch(name -> name.equals("x-id") ||
                name.equals("x-amz-acl") ||
                name.equals("x-amz-sdk-checksum-algorithm") ||
                name.startsWith("x-amz-checksum-") && name.equals(name.toLowerCase(Locale.ROOT)));
    }

    private static boolean isObjectOperationQuery(Map<String, String> query) {
        return query.size() == 1 &&
            ("PutObject".equals(query.get("x-id")) || "CopyObject".equals(query.get("x-id")) ||
             "GetObject".equals(query.get("x-id")) || "HeadObject".equals(query.get("x-id")) ||
             "DeleteObject".equals(query.get("x-id")));
    }

    private static void validateUploadHeaders(HttpExchange exchange, boolean copy) {
        if (!exchange.getRequestMethod().equals("PUT") && (copy || exchange.getRequestHeaders().containsKey("content-md5") ||
            exchange.getRequestHeaders().containsKey("x-amz-metadata-directive") ||
            exchange.getRequestHeaders().containsKey("x-amz-tagging") ||
            exchange.getRequestHeaders().containsKey("x-amz-tagging-directive") ||
            exchange.getRequestHeaders().containsKey("x-amz-sdk-checksum-algorithm") ||
            exchange.getRequestHeaders().keySet().stream().anyMatch(name ->
                name.toLowerCase(Locale.ROOT).startsWith("x-amz-checksum-") &&
                !name.equalsIgnoreCase("x-amz-checksum-mode"))))
            S3Request.unsupported("Object upload header");
    }

    private void handleObjectMethod(HttpExchange exchange, String key, Map<String, String> query,
                                    SigV4.Verified verified, String bucketName, boolean copy) throws IOException {
        String principal = verified.principal();
        switch (exchange.getRequestMethod()) {
            case "PUT" -> {
                access.requireWrite(bucketName, key, principal, "s3:PutObject");
                if (copy) copyObject(exchange, key, bucketName, principal);
                else putObject(exchange, key, verified, bucketName, query);
            }
            case "GET", "HEAD" -> readObject(exchange, key, bucketName, query.get("versionId"), principal);
            case "DELETE" -> {
                access.requireWrite(bucketName, key, principal,
                    query.get("versionId") == null ? "s3:DeleteObject" : "s3:DeleteObjectVersion");
                deleteObject(exchange, key, bucketName, query.get("versionId"));
            }
            default -> S3Request.unsupported("HTTP method");
        }
    }

    private void putObject(HttpExchange exchange, String key, SigV4.Verified verified,
                           String bucketName, Map<String, String> query) throws IOException {
        var headers = exchange.getRequestHeaders();
        String length = SigV4.single(headers, "content-length"), condition = SigV4.single(headers, "if-none-match");
        if (condition != null && !condition.equals("*")) S3Request.unsupported("Write condition");
        long bytes = contentLength(length);
        validatePutHeaders(headers, verified.streaming());
        UploadChecksums checksums = S3Request.uploadChecksums(headers, query);
        AwsChunkedInputStream chunked = verified.streaming() ? S3Request.chunkedBody(exchange, verified) : null;
        bytes = uploadLength(headers, chunked, bytes);
        String type = S3Request.contentType(headers);
        Map<String, String> metadata = ObjectAttributes.userMetadata(headers);
        Map<String, String> tags = ObjectAttributes.tagsHeader(SigV4.single(headers, "x-amz-tagging"));
        Map<String, String> acl = uploadAcl(headers, bucketName, verified.principal());
        ObjectStorage.Metadata data = store.put(bucketName, key,
            checksums.verifying(chunked == null ? exchange.getRequestBody() : chunked),
            bytes, S3Request.expectedPayloadHash(verified.payload()), checksums.sha256(), condition != null, type,
            metadata, tags, () -> uploadChecksumMetadata(headers, chunked, checksums), acl);
        exchange.getResponseHeaders().set("ETag", "\"" + data.etag() + "\"");
        if (data.versionId() != null) exchange.getResponseHeaders().set("x-amz-version-id", data.versionId());
        exchange.getResponseHeaders().set("x-amz-checksum-sha256", Base64.getEncoder().encodeToString(data.sha256()));
        data.checksums().forEach(exchange.getResponseHeaders()::set);
        exchange.sendResponseHeaders(200, -1);
    }

    private static long contentLength(String length) {
        try { return length == null ? -1 : Long.parseLong(length); }
        catch (NumberFormatException e) { throw new StoreException(400, "InvalidArgument", "Invalid Content-Length"); }
    }

    private static void validatePutHeaders(Headers headers, boolean streaming) {
        if (!streaming && headers.containsKey("content-encoding")) S3Request.unsupported("Encoded payload");
        if (headers.containsKey("x-amz-metadata-directive")) S3Request.unsupported("Copy metadata directive");
        if (headers.containsKey("x-amz-tagging-directive")) S3Request.unsupported("Copy tagging directive");
    }

    private static long uploadLength(Headers headers, AwsChunkedInputStream chunked, long bytes) {
        if (chunked != null) return S3Request.decodedLength(headers);
        if (headers.containsKey("x-amz-decoded-content-length") || headers.containsKey("x-amz-trailer"))
            S3Request.unsupported("Chunked upload header");
        return bytes;
    }

    private Map<String, String> uploadAcl(Headers headers, String bucketName, String principal) throws IOException {
        Map<String, String> acl = Acl.fromHeaders(headers, authentication.identities());
        BucketSecurity.checkAcl(store.bucket(bucketName), headers, acl, false);
        if (!acl.isEmpty() && !owner.equals(principal))
            access.requireBucket(bucketName, principal, Acl.WRITE_ACP);
        return acl;
    }

    private static Map<String, String> uploadChecksumMetadata(Headers headers, AwsChunkedInputStream chunked,
                                                             UploadChecksums checksums) {
        if (chunked == null || chunked.trailerValue() == null) return checksums.metadata();
        String trailer = SigV4.single(headers, "x-amz-trailer");
        if (!checksums.metadata().isEmpty())
            throw new StoreException(400, "InvalidRequest", "Supply one checksum algorithm");
        return Map.of(trailer, chunked.trailerValue());
    }

    private void copyObject(HttpExchange exchange, String key, String bucketName,
                            String principal) throws IOException {
        var headers = exchange.getRequestHeaders();
        validateCopyHeaders(headers);
        CopySource source = copySource(headers);
        CopyDirectives directives = copyDirectives(headers);
        try (var object = store.open(source.bucket(), source.key(), source.versionId())) {
            var sourceMetadata = object.metadata();
            access.requireObject(source.bucket(), source.key(), sourceMetadata, principal, Acl.READ,
                source.versionId() == null ? "s3:GetObject" : "s3:GetObjectVersion");
            if (sourceMetadata.versionId() != null)
                exchange.getResponseHeaders().set("x-amz-copy-source-version-id", sourceMetadata.versionId());
            String type = "REPLACE".equals(directives.metadata())
                ? S3Request.contentType(headers) : sourceMetadata.contentType();
            Map<String, String> metadata = "REPLACE".equals(directives.metadata())
                ? ObjectAttributes.userMetadata(headers) : sourceMetadata.userMetadata();
            Map<String, String> tags = "REPLACE".equals(directives.tagging())
                ? ObjectAttributes.tagsHeader(SigV4.single(headers, "x-amz-tagging")) : sourceMetadata.tags();
            Map<String, String> acl = uploadAcl(headers, bucketName, principal);
            var copied = store.put(bucketName, key, object.stream(), sourceMetadata.length(),
                SigV4.hex(sourceMetadata.sha256()), null, false, type, metadata, tags,
                sourceMetadata::checksums, acl);
            if (copied.versionId() != null)
                exchange.getResponseHeaders().set("x-amz-version-id", copied.versionId());
            S3Xml.sendXml(exchange, 200, "<CopyObjectResult><LastModified>" +
                Instant.ofEpochMilli(copied.modified()) + "</LastModified><ETag>&quot;" +
                copied.etag() + "&quot;</ETag></CopyObjectResult>");
        }
    }

    private static void validateCopyHeaders(Headers headers) {
        if (headers.containsKey("content-encoding") || headers.containsKey("content-md5") ||
            headers.containsKey("if-none-match") || headers.containsKey("x-amz-sdk-checksum-algorithm") ||
            headers.keySet().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).startsWith("x-amz-checksum-")))
            S3Request.unsupported("Copy request header");
    }

    private CopySource copySource(Headers headers) throws IOException {
        String source = SigV4.single(headers, "x-amz-copy-source");
        if (source == null) throw new StoreException(400, "InvalidArgument", "Missing copy source");
        if (source.startsWith("/")) source = source.substring(1);
        String sourceVersion = null;
        int question = source.indexOf('?');
        if (question >= 0) {
            Map<String, String> sourceQuery = S3Request.query(source.substring(question + 1));
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
        return new CopySource(sourceBucket, sourceKey, sourceVersion);
    }

    private static CopyDirectives copyDirectives(Headers headers) {
        String directive = SigV4.single(headers, "x-amz-metadata-directive");
        if (directive != null && !directive.equals("COPY") && !directive.equals("REPLACE"))
            throw new StoreException(400, "InvalidArgument", "Invalid metadata directive");
        String tagDirective = SigV4.single(headers, "x-amz-tagging-directive");
        if (tagDirective != null && !tagDirective.equals("COPY") && !tagDirective.equals("REPLACE"))
            throw new StoreException(400, "InvalidArgument", "Invalid tagging directive");
        if (!"REPLACE".equals(tagDirective) && headers.containsKey("x-amz-tagging"))
            S3Request.unsupported("Tagging header requires REPLACE tagging directive");
        if (!"REPLACE".equals(directive) && !ObjectAttributes.userMetadata(headers).isEmpty())
            S3Request.unsupported("User metadata requires REPLACE metadata directive");
        return new CopyDirectives(directive, tagDirective);
    }

    private void deleteObject(HttpExchange exchange, String key, String bucketName,
                              String versionId) throws IOException {
        if (exchange.getRequestHeaders().containsKey("if-none-match")) S3Request.unsupported("Conditional delete");
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
            S3Request.unsupported("Tagging request header");
        switch (method) {
            case "GET" -> {
                S3Request.requireEmptyBody(exchange, hash);
                if (versionId != null) exchange.getResponseHeaders().set("x-amz-version-id", versionId);
                S3Xml.sendXml(exchange, 200, S3Xml.tagXml(store.tags(bucketName, key, versionId)));
            }
            case "PUT" -> {
                byte[] body = S3Request.signedBody(exchange, hash, 65536);
                S3Request.verifyContentMd5(exchange.getRequestHeaders(), body);
                store.setTags(bucketName, key, versionId, S3Xml.parseTags(body));
                if (versionId != null) exchange.getResponseHeaders().set("x-amz-version-id", versionId);
                exchange.sendResponseHeaders(200, -1);
            }
            case "DELETE" -> {
                S3Request.requireEmptyBody(exchange, hash);
                store.setTags(bucketName, key, versionId, Map.of());
                if (versionId != null) exchange.getResponseHeaders().set("x-amz-version-id", versionId);
                exchange.sendResponseHeaders(204, -1);
            }
            default -> S3Request.unsupported("Tagging operation");
        }
    }

    private void readObject(HttpExchange exchange, String key, String bucketName,
                            String versionId, String principal) throws IOException {
        var headers = exchange.getRequestHeaders();
        String checksumMode = validateReadHeaders(headers);
        try (var object = store.open(bucketName, key, versionId)) {
            var meta = object.metadata();
            access.requireObject(bucketName, key, meta, principal, Acl.READ,
                versionId == null ? "s3:GetObject" : "s3:GetObjectVersion");
            String etag = "\"" + meta.etag() + "\"";
            String noneMatch = SigV4.single(headers, "if-none-match");
            if (matchesEtag(noneMatch, etag)) {
                exchange.getResponseHeaders().set("ETag", etag);
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            S3Request.Range range = readRange(exchange, meta.length());
            setReadHeaders(exchange.getResponseHeaders(), meta, range, etag, checksumMode);
            sendReadBody(exchange, object.stream(), range);
        }
    }

    private static String validateReadHeaders(Headers headers) {
        String checksumMode = SigV4.single(headers, "x-amz-checksum-mode");
        if (checksumMode != null && !checksumMode.equals("ENABLED"))
            throw new StoreException(400, "InvalidArgument", "Invalid checksum mode");
        if (headers.containsKey("if-match") || headers.containsKey("if-modified-since") ||
            headers.containsKey("if-unmodified-since")) S3Request.unsupported("Conditional read");
        return checksumMode;
    }

    private static boolean matchesEtag(String noneMatch, String etag) {
        return noneMatch != null && (noneMatch.equals("*") ||
            java.util.Arrays.stream(noneMatch.split(",")).map(String::trim).anyMatch(etag::equals));
    }

    private static S3Request.Range readRange(HttpExchange exchange, long length) {
        try { return S3Request.range(SigV4.single(exchange.getRequestHeaders(), "range"), length); }
        catch (StoreException error) {
            if (error.status == 416) exchange.getResponseHeaders().set("Content-Range", "bytes */" + length);
            throw error;
        }
    }

    private static void setReadHeaders(Headers response, ObjectStorage.Metadata meta, S3Request.Range range,
                                       String etag, String checksumMode) {
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
    }

    private static void sendReadBody(HttpExchange exchange, InputStream input, S3Request.Range range) throws IOException {
        int status = range.partial() ? 206 : 200;
        if (exchange.getRequestMethod().equals("HEAD") || range.length() == 0) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        input.skipNBytes(range.start());
        exchange.sendResponseHeaders(status, range.length());
        byte[] buffer = new byte[65536];
        long left = range.length();
        while (left > 0) {
            int n = input.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) throw new IOException("Object body ended before its recorded length");
            exchange.getResponseBody().write(buffer, 0, n);
            left -= n;
        }
    }

    private record CopySource(String bucket, String key, String versionId) {}
    private record CopyDirectives(String metadata, String tagging) {}
}
