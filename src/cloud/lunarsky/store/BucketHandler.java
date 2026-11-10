package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

final class BucketHandler {
    private final ObjectStorage store;
    private final MultipartStorage multipart;
    private final MultipartHandler multipartHandler;
    private final SigV4 authentication;
    private final S3Access access;
    private final String owner;
    private final String bucket;

    BucketHandler(ObjectStorage store, MultipartStorage multipart, MultipartHandler multipartHandler,
                  SigV4 authentication, S3Access access, String bucket) {
        this.store = store;
        this.multipart = multipart;
        this.multipartHandler = multipartHandler;
        this.authentication = authentication;
        this.access = access;
        this.owner = access.owner();
        this.bucket = bucket;
    }

    void listBuckets(HttpExchange exchange) throws IOException {
        StringBuilder body = new StringBuilder("<ListAllMyBucketsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
            "<Owner><ID>" + owner + "</ID><DisplayName>ObjectStore</DisplayName></Owner><Buckets>");
        for (ObjectStorage.Bucket entry : store.buckets())
            body.append("<Bucket><Name>").append(S3Xml.xml(entry.name())).append("</Name><CreationDate>")
                .append(Instant.ofEpochMilli(entry.created())).append("</CreationDate></Bucket>");
        S3Xml.sendXml(exchange, 200, body.append("</Buckets></ListAllMyBucketsResult>").toString());
    }

    void handleBucket(HttpExchange exchange, Map<String, String> query, String hash,
                      String bucketName, String principal) throws IOException {
        String method = exchange.getRequestMethod();
        if (configurationRequest(query, "ownershipControls")) {
            access.requireOwner(principal);
            handleOwnership(exchange, hash, bucketName);
        } else if (configurationRequest(query, "publicAccessBlock")) {
            access.requireOwner(principal);
            handlePublicAccess(exchange, hash, bucketName);
        } else if (configurationRequest(query, "policy")) {
            access.requireOwner(principal);
            handlePolicy(exchange, hash, bucketName);
        } else if (aclRequest(query, method)) {
            handleAcl(exchange, hash, bucketName, principal);
        } else if (bucketRequest(query, method)) {
            handleBucketOperation(exchange, hash, bucketName, principal);
        } else {
            store.bucket(bucketName);
            handleExistingBucket(exchange, query, hash, bucketName, principal);
        }
    }

    private static boolean configurationRequest(Map<String, String> query, String configuration) {
        return query.size() >= 1 && query.size() <= 2 &&
            query.keySet().stream().allMatch(java.util.Set.of(configuration, "x-id")::contains) &&
            query.containsKey(configuration) && query.get(configuration).isEmpty();
    }

    private static boolean aclRequest(Map<String, String> query, String method) {
        return query.containsKey("acl") && query.get("acl").isEmpty() &&
            query.keySet().stream().allMatch(java.util.Set.of("acl", "x-id")::contains) &&
            (!query.containsKey("x-id") ||
             query.get("x-id").equals(method.equals("GET") ? "GetBucketAcl" : "PutBucketAcl")) &&
            (method.equals("GET") || method.equals("PUT"));
    }

    private static boolean bucketRequest(Map<String, String> query, String method) {
        String operation = switch (method) {
            case "PUT" -> "CreateBucket";
            case "HEAD" -> "HeadBucket";
            case "DELETE" -> "DeleteBucket";
            default -> null;
        };
        return operation != null && (query.isEmpty() ||
            query.size() == 1 && operation.equals(query.get("x-id")));
    }

    private void handleOwnership(HttpExchange exchange, String hash, String bucketName) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                S3Request.requireEmptyBody(exchange, hash);
                S3Xml.sendXml(exchange, 200, BucketSecurity.ownershipXml(store.bucket(bucketName).ownership()));
            }
            case "PUT" -> {
                byte[] body = S3Request.signedBody(exchange, hash, 65536);
                S3Request.verifyContentMd5(exchange.getRequestHeaders(), body);
                store.setBucketOwnership(bucketName, BucketSecurity.parseOwnership(body));
                exchange.sendResponseHeaders(200, -1);
            }
            case "DELETE" -> {
                S3Request.requireEmptyBody(exchange, hash);
                store.setBucketOwnership(bucketName, ObjectStorage.ObjectOwnership.OBJECT_WRITER);
                exchange.sendResponseHeaders(204, -1);
            }
            default -> S3Request.unsupported("Ownership controls operation");
        }
    }

    private void handlePublicAccess(HttpExchange exchange, String hash, String bucketName) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                S3Request.requireEmptyBody(exchange, hash);
                S3Xml.sendXml(exchange, 200, BucketSecurity.publicAccessXml(
                    store.bucket(bucketName).publicAccessBlock()));
            }
            case "PUT" -> {
                byte[] body = S3Request.signedBody(exchange, hash, 65536);
                S3Request.verifyContentMd5(exchange.getRequestHeaders(), body);
                store.setPublicAccessBlock(bucketName, BucketSecurity.parsePublicAccess(body));
                exchange.sendResponseHeaders(200, -1);
            }
            case "DELETE" -> {
                S3Request.requireEmptyBody(exchange, hash);
                store.setPublicAccessBlock(bucketName, ObjectStorage.PublicAccessBlock.open());
                exchange.sendResponseHeaders(204, -1);
            }
            default -> S3Request.unsupported("Public access block operation");
        }
    }

    private void handlePolicy(HttpExchange exchange, String hash, String bucketName) throws IOException {
        var current = store.bucket(bucketName);
        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                S3Request.requireEmptyBody(exchange, hash);
                if (current.policy() == null)
                    throw new StoreException(404, "NoSuchBucketPolicy", "Bucket has no policy");
                byte[] body = current.policy().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            case "PUT" -> {
                byte[] body = S3Request.signedBody(exchange, hash, 20480);
                S3Request.verifyContentMd5(exchange.getRequestHeaders(), body);
                String policy = new String(body, StandardCharsets.UTF_8);
                BucketPolicy parsed = BucketPolicy.parse(policy, bucketName);
                if (current.publicAccessBlock().blockPublicPolicy() && parsed.publicAllow())
                    throw new StoreException(403, "AccessDenied", "Public bucket policies are blocked");
                store.setBucketPolicy(bucketName, policy);
                exchange.sendResponseHeaders(204, -1);
            }
            case "DELETE" -> {
                S3Request.requireEmptyBody(exchange, hash);
                store.setBucketPolicy(bucketName, null);
                exchange.sendResponseHeaders(204, -1);
            }
            default -> S3Request.unsupported("Bucket policy operation");
        }
    }

    private void handleAcl(HttpExchange exchange, String hash, String bucketName, String principal) throws IOException {
        var current = store.bucket(bucketName);
        boolean get = exchange.getRequestMethod().equals("GET");
        access.requireBucket(bucketName, principal, get ? Acl.READ_ACP : Acl.WRITE_ACP);
        if (get) {
            S3Request.requireEmptyBody(exchange, hash);
            S3Xml.sendXml(exchange, 200, Acl.xml(
                current.ownership() == ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED
                    ? Map.of() : current.acl(), owner));
        } else {
            if (current.ownership() == ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED)
                throw new StoreException(400, "AccessControlListNotSupported", "ACLs are disabled for this bucket");
            Map<String, String> acl = readAcl(exchange, hash);
            BucketSecurity.checkAcl(current, exchange.getRequestHeaders(), acl, true);
            store.setBucketAcl(bucketName, acl);
            exchange.sendResponseHeaders(200, -1);
        }
    }

    private Map<String, String> readAcl(HttpExchange exchange, String hash) throws IOException {
        if (exchange.getRequestHeaders().containsKey("x-amz-acl") ||
            exchange.getRequestHeaders().keySet().stream().anyMatch(
                name -> name.toLowerCase(Locale.ROOT).startsWith("x-amz-grant-"))) {
            S3Request.requireEmptyBody(exchange, hash);
            S3Request.verifyContentMd5(exchange.getRequestHeaders(), new byte[0]);
            return Acl.fromHeaders(exchange.getRequestHeaders(), authentication.identities());
        }
        byte[] body = S3Request.signedBody(exchange, hash, 65536);
        S3Request.verifyContentMd5(exchange.getRequestHeaders(), body);
        return Acl.fromXml(body, owner, authentication.identities());
    }

    private void handleBucketOperation(HttpExchange exchange, String hash,
                                       String bucketName, String principal) throws IOException {
        S3Request.requireEmptyBody(exchange, hash);
        switch (exchange.getRequestMethod()) {
            case "PUT" -> {
                access.requireOwner(principal);
                createBucket(exchange, bucketName);
            }
            case "HEAD" -> {
                access.requireBucket(bucketName, principal, Acl.READ);
                exchange.sendResponseHeaders(200, -1);
            }
            case "DELETE" -> {
                access.requireOwner(principal);
                deleteBucket(exchange, bucketName);
            }
        }
    }

    private void createBucket(HttpExchange exchange, String bucketName) throws IOException {
        String requestedOwnership = SigV4.single(exchange.getRequestHeaders(), "x-amz-object-ownership");
        ObjectStorage.ObjectOwnership ownership = requestedOwnership == null
            ? ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED
            : BucketSecurity.ownership(requestedOwnership);
        Map<String, String> acl = Acl.fromHeaders(exchange.getRequestHeaders(), authentication.identities());
        BucketSecurity.checkAcl(new ObjectStorage.Bucket(bucketName, 0,
            ObjectStorage.VersioningState.NEVER, Map.of(), ownership,
            ObjectStorage.PublicAccessBlock.secure(), null),
            exchange.getRequestHeaders(), acl, false);
        store.createBucket(bucketName);
        if (requestedOwnership != null && ownership != ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED)
            store.setBucketOwnership(bucketName, ownership);
        if (!acl.isEmpty()) store.setBucketAcl(bucketName, acl);
        exchange.getResponseHeaders().set("Location", "/" + bucketName);
        exchange.sendResponseHeaders(200, -1);
    }

    private void deleteBucket(HttpExchange exchange, String bucketName) throws IOException {
        if (bucketName.equals(bucket))
            throw new StoreException(409, "BucketNotEmpty", "The configured default bucket cannot be deleted");
        synchronized (multipart) {
            if (!multipart.listUploads(bucketName, "").isEmpty())
                throw new StoreException(409, "BucketNotEmpty", "Bucket has active uploads");
            store.deleteBucket(bucketName);
        }
        exchange.sendResponseHeaders(204, -1);
    }

    private void handleExistingBucket(HttpExchange exchange, Map<String, String> query, String hash,
                                      String bucketName, String principal) throws IOException {
        String method = exchange.getRequestMethod();
        if (versioningRequest(query, method)) {
            access.requireOwner(principal);
            handleVersioning(exchange, hash, bucketName);
        } else if (method.equals("GET") && query.containsKey("versions") && query.get("versions").isEmpty()) {
            access.requireOwner(principal);
            handleListVersions(exchange, query, hash, bucketName);
        } else if (method.equals("GET") && query.containsKey("uploads")) {
            access.requireOwner(principal);
            handleListUploads(exchange, query, hash, bucketName);
        } else {
            handleListObjects(exchange, query, hash, bucketName, principal);
        }
    }

    private static boolean versioningRequest(Map<String, String> query, String method) {
        String operation = method.equals("GET") ? "GetBucketVersioning" :
            method.equals("PUT") ? "PutBucketVersioning" : null;
        return query.containsKey("versioning") && query.get("versioning").isEmpty() &&
            (query.size() == 1 || query.size() == 2 && operation != null &&
             operation.equals(query.get("x-id")));
    }

    private void handleListVersions(HttpExchange exchange, Map<String, String> query,
                                    String hash, String bucketName) throws IOException {
        if (!query.keySet().stream().allMatch(java.util.Set.of("versions", "prefix", "key-marker",
            "version-id-marker", "max-keys", "encoding-type", "x-id")::contains) ||
            query.containsKey("x-id") && !"ListObjectVersions".equals(query.get("x-id")))
            S3Request.unsupported("Version listing option");
        S3Request.requireEmptyBody(exchange, hash);
        listVersions(exchange, query, bucketName);
    }

    private void handleListUploads(HttpExchange exchange, Map<String, String> query,
                                   String hash, String bucketName) throws IOException {
        if (!query.get("uploads").isEmpty() ||
            !query.keySet().stream().allMatch(java.util.Set.of("uploads", "prefix", "key-marker",
                "upload-id-marker", "max-uploads", "x-id")::contains) ||
            (query.containsKey("x-id") && !"ListMultipartUploads".equals(query.get("x-id"))))
            S3Request.unsupported("Bucket operation");
        S3Request.requireEmptyBody(exchange, hash);
        multipartHandler.listUploads(exchange, query, bucketName);
    }

    private void handleListObjects(HttpExchange exchange, Map<String, String> query, String hash,
                                   String bucketName, String principal) throws IOException {
        if (!exchange.getRequestMethod().equals("GET") || !"2".equals(query.get("list-type")) ||
            !query.keySet().stream().allMatch(java.util.Set.of("list-type", "prefix", "delimiter", "max-keys",
                "continuation-token", "start-after", "encoding-type", "x-id")::contains) ||
            (query.containsKey("x-id") && !"ListObjectsV2".equals(query.get("x-id"))))
            S3Request.unsupported("Bucket operation");
        access.requireBucket(bucketName, principal, Acl.READ);
        S3Request.requireEmptyBody(exchange, hash);
        listObjects(exchange, query, bucketName);
    }

    private void handleVersioning(HttpExchange exchange, String hash, String bucketName) throws IOException {
        switch (exchange.getRequestMethod()) {
            case "GET" -> {
                S3Request.requireEmptyBody(exchange, hash);
                var state = store.bucket(bucketName).versioning();
                String status = state == ObjectStorage.VersioningState.NEVER ? "" :
                    "<Status>" + state.name().charAt(0) + state.name().substring(1).toLowerCase(Locale.ROOT) +
                    "</Status>";
                S3Xml.sendXml(exchange, 200, "<VersioningConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                    status + "</VersioningConfiguration>");
            }
            case "PUT" -> {
                byte[] body = S3Request.signedBody(exchange, hash, 65536);
                S3Request.verifyContentMd5(exchange.getRequestHeaders(), body);
                store.setVersioning(bucketName, S3Xml.parseVersioning(body));
                exchange.sendResponseHeaders(200, -1);
            }
            default -> S3Request.unsupported("Bucket versioning operation");
        }
    }

    private void listVersions(HttpExchange exchange, Map<String, String> query,
                              String bucketName) throws IOException {
        String encoding = query.get("encoding-type");
        if (encoding != null && !encoding.equals("url")) S3Request.unsupported("Encoding type");
        int maximum = S3Request.boundedNumber(query.get("max-keys"), 0, 1000, 1000);
        String keyMarker = query.get("key-marker");
        String versionMarker = query.get("version-id-marker");
        if (versionMarker != null && keyMarker == null)
            throw new StoreException(400, "InvalidArgument", "Version ID marker requires a key marker");
        var page = store.listVersions(bucketName, query.getOrDefault("prefix", ""),
            keyMarker, versionMarker, maximum);
        StringBuilder body = new StringBuilder("<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Name>")
            .append(S3Xml.xml(bucketName)).append("</Name><Prefix>")
            .append(S3Xml.xml(S3Xml.listKey(query.getOrDefault("prefix", ""), encoding))).append("</Prefix><KeyMarker>")
            .append(S3Xml.xml(S3Xml.listKey(query.getOrDefault("key-marker", ""), encoding)))
            .append("</KeyMarker><VersionIdMarker>")
            .append(S3Xml.xml(query.getOrDefault("version-id-marker", "")))
            .append("</VersionIdMarker><MaxKeys>").append(maximum)
            .append("</MaxKeys><IsTruncated>").append(page.truncated()).append("</IsTruncated>");
        if (encoding != null) body.append("<EncodingType>url</EncodingType>");
        if (page.truncated()) body.append("<NextKeyMarker>")
            .append(S3Xml.xml(S3Xml.listKey(page.nextKey(), encoding))).append("</NextKeyMarker><NextVersionIdMarker>")
            .append(S3Xml.xml(page.nextVersionId())).append("</NextVersionIdMarker>");
        for (var entry : page.entries()) {
            body.append(entry.deleteMarker() ? "<DeleteMarker>" : "<Version>")
                .append("<Key>").append(S3Xml.xml(S3Xml.listKey(entry.key(), encoding)))
                .append("</Key><VersionId>").append(S3Xml.xml(entry.versionId()))
                .append("</VersionId><IsLatest>").append(entry.latest())
                .append("</IsLatest><LastModified>")
                .append(Instant.ofEpochMilli(entry.modified())).append("</LastModified>");
            if (!entry.deleteMarker()) body.append("<ETag>&quot;").append(entry.metadata().etag())
                .append("&quot;</ETag><Size>").append(entry.metadata().length()).append("</Size>");
            body.append("<Owner><ID>objectstore</ID><DisplayName>ObjectStore</DisplayName></Owner>")
                .append(entry.deleteMarker() ? "</DeleteMarker>" : "<StorageClass>STANDARD</StorageClass></Version>");
        }
        S3Xml.sendXml(exchange, 200, body.append("</ListVersionsResult>").toString());
    }

    private void listObjects(HttpExchange exchange, Map<String, String> query, String bucketName) throws IOException {
        S3Request.ListRequest request = S3Request.listRequest(query);
        var page = store.list(bucketName, request.prefix(), request.delimiter(), request.maxKeys(), request.after());
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
        S3Xml.appendListHeader(xml, query, request, page, bucketName);
        S3Xml.appendListEntries(xml, page, request.encoding());
        if (page.truncated()) xml.append("<NextContinuationToken>")
            .append(Base64.getUrlEncoder().withoutPadding().encodeToString(page.nextKey().getBytes(StandardCharsets.UTF_8)))
            .append("</NextContinuationToken>");
        xml.append("</ListBucketResult>");
        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }
}
