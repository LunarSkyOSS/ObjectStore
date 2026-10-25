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
        if (query.containsKey("acl") && query.get("acl").isEmpty() &&
            query.keySet().stream().allMatch(java.util.Set.of("acl", "x-id")::contains) &&
            (!query.containsKey("x-id") ||
             query.get("x-id").equals(method.equals("GET") ? "GetBucketAcl" : "PutBucketAcl")) &&
            (method.equals("GET") || method.equals("PUT"))) {
            var current = store.bucket(bucketName);
            Acl.require(current.acl(), principal, owner,
                method.equals("GET") ? Acl.READ_ACP : Acl.WRITE_ACP);
            if (method.equals("GET")) {
                S3Request.requireEmptyBody(exchange, hash);
                S3Xml.sendXml(exchange, 200, Acl.xml(current.acl(), owner));
            } else {
                if (exchange.getRequestHeaders().containsKey("x-amz-acl") ||
                    exchange.getRequestHeaders().keySet().stream().anyMatch(
                        name -> name.toLowerCase(Locale.ROOT).startsWith("x-amz-grant-"))) {
                    S3Request.requireEmptyBody(exchange, hash);
                    S3Request.verifyContentMd5(exchange.getRequestHeaders(), new byte[0]);
                    store.setBucketAcl(bucketName, Acl.fromHeaders(exchange.getRequestHeaders(),
                        authentication.identities()));
                } else {
                    byte[] body = S3Request.signedBody(exchange, hash, 65536);
                    S3Request.verifyContentMd5(exchange.getRequestHeaders(), body);
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
            S3Request.requireEmptyBody(exchange, hash);
            switch (method) {
                case "PUT" -> {
                    access.requireOwner(principal);
                    Map<String, String> acl = Acl.fromHeaders(exchange.getRequestHeaders(),
                        authentication.identities());
                    store.createBucket(bucketName);
                    if (!acl.isEmpty()) store.setBucketAcl(bucketName, acl);
                    exchange.getResponseHeaders().set("Location", "/" + bucketName);
                    exchange.sendResponseHeaders(200, -1);
                }
                case "HEAD" -> {
                    access.requireBucket(bucketName, principal, Acl.READ);
                    exchange.sendResponseHeaders(200, -1);
                }
                case "DELETE" -> {
                    access.requireOwner(principal);
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
            access.requireOwner(principal);
            handleVersioning(exchange, hash, bucketName);
            return;
        }
        if (method.equals("GET") && query.containsKey("versions") && query.get("versions").isEmpty()) {
            access.requireOwner(principal);
            if (!query.keySet().stream().allMatch(java.util.Set.of("versions", "prefix", "key-marker",
                "version-id-marker", "max-keys", "encoding-type", "x-id")::contains) ||
                query.containsKey("x-id") && !"ListObjectVersions".equals(query.get("x-id")))
                S3Request.unsupported("Version listing option");
            S3Request.requireEmptyBody(exchange, hash);
            listVersions(exchange, query, bucketName);
            return;
        }
        if (exchange.getRequestMethod().equals("GET") && query.containsKey("uploads")) {
            access.requireOwner(principal);
            if (!query.get("uploads").isEmpty() ||
                !query.keySet().stream().allMatch(java.util.Set.of("uploads", "prefix", "key-marker",
                    "upload-id-marker", "max-uploads", "x-id")::contains) ||
                (query.containsKey("x-id") && !"ListMultipartUploads".equals(query.get("x-id"))))
                S3Request.unsupported("Bucket operation");
            S3Request.requireEmptyBody(exchange, hash);
            multipartHandler.listUploads(exchange, query, bucketName);
            return;
        }
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
