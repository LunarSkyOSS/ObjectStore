package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsExchange;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class MultipartHandler {
    private final ObjectStorage store;
    private final MultipartStorage multipart;
    private final SigV4 authentication;
    private final S3Access access;
    private final String owner;
    private final URI publicEndpoint;

    MultipartHandler(ObjectStorage store, MultipartStorage multipart, SigV4 authentication,
                     S3Access access, URI publicEndpoint) {
        this.store = store;
        this.multipart = multipart;
        this.authentication = authentication;
        this.access = access;
        this.owner = authentication.root();
        this.publicEndpoint = publicEndpoint;
    }

    void handleMultipart(HttpExchange exchange, String method, Map<String, String> query,
                                 String key, SigV4.Verified verified, String bucketName) throws IOException {
        String hash = verified.payload();
        var headers = exchange.getRequestHeaders();
        if ((headers.containsKey("content-encoding") && !(method.equals("PUT") && verified.streaming())) ||
            headers.containsKey("if-none-match"))
            S3Request.unsupported("Multipart request header");
        if (headers.containsKey("x-amz-copy-source") || headers.containsKey("x-amz-metadata-directive") ||
            headers.containsKey("x-amz-tagging-directive"))
            S3Request.unsupported("Multipart copy request");
        if (!query.containsKey("uploads") &&
            (headers.containsKey("x-amz-tagging") || !ObjectAttributes.userMetadata(headers).isEmpty() ||
             headers.containsKey("x-amz-acl") || headers.keySet().stream().anyMatch(
                 name -> name.toLowerCase(Locale.ROOT).startsWith("x-amz-grant-"))))
            S3Request.unsupported("Multipart object attributes belong on initiation");
        boolean initiate = query.containsKey("uploads");
        if (!method.equals("PUT") && (headers.containsKey("content-md5") ||
            headers.containsKey("x-amz-sdk-checksum-algorithm") ||
            headers.keySet().stream().anyMatch(name -> {
                String lower = name.toLowerCase(Locale.ROOT);
                return lower.startsWith("x-amz-checksum-") &&
                    !(initiate && lower.equals("x-amz-checksum-algorithm"));
            })))
            S3Request.unsupported("Multipart checksum header");
        if (initiate) {
            String algorithm = SigV4.single(headers, "x-amz-checksum-algorithm");
            if (algorithm != null && !algorithm.equals("CRC32"))
                S3Request.unsupported("Multipart checksum algorithm");
            S3Request.requireEmptyBody(exchange, hash);
            Map<String, String> acl = Acl.fromHeaders(headers, authentication.identities());
            BucketSecurity.checkAcl(store.bucket(bucketName), headers, acl, false);
            if (!acl.isEmpty() && !owner.equals(verified.principal()))
                access.requireBucket(bucketName, verified.principal(), Acl.WRITE_ACP);
            String id = multipart.create(bucketName, key, S3Request.contentType(headers),
                ObjectAttributes.userMetadata(headers),
                ObjectAttributes.tagsHeader(SigV4.single(headers, "x-amz-tagging")), acl);
            S3Xml.sendXml(exchange, 200, "<InitiateMultipartUploadResult><Bucket>" + S3Xml.xml(bucketName) +
                "</Bucket><Key>" + S3Xml.xml(key) + "</Key><UploadId>" + id +
                "</UploadId></InitiateMultipartUploadResult>");
            return;
        }
        String id = query.get("uploadId");
        switch (method) {
            case "PUT" -> {
                int number;
                try { number = Integer.parseInt(query.get("partNumber")); }
                catch (NumberFormatException e) { throw new StoreException(400, "InvalidArgument", "Invalid part number"); }
                long length = verified.streaming() ? S3Request.decodedLength(headers) : S3Request.contentLength(headers);
                UploadChecksums checksums = UploadChecksums.from(headers);
                AwsChunkedInputStream chunked = verified.streaming() ? S3Request.chunkedBody(exchange, verified) : null;
                String etag = multipart.putPart(id, bucketName, key, number,
                    checksums.verifying(chunked == null ? exchange.getRequestBody() : chunked),
                    length, S3Request.expectedPayloadHash(hash), checksums.sha256());
                exchange.getResponseHeaders().set("ETag", "\"" + etag + "\"");
                checksums.response(exchange.getResponseHeaders());
                if (chunked != null && chunked.trailerValue() != null)
                    exchange.getResponseHeaders().set(SigV4.single(headers, "x-amz-trailer"), chunked.trailerValue());
                exchange.sendResponseHeaders(200, -1);
            }
            case "POST" -> {
                byte[] body = S3Request.signedBody(exchange, hash, 65536);
                List<MultipartStorage.Part> parts = S3Xml.completedParts(body);
                var meta = multipart.complete(id, bucketName, key, parts);
                if (meta.versionId() != null)
                    exchange.getResponseHeaders().set("x-amz-version-id", meta.versionId());
                S3Xml.sendXml(exchange, 200, "<CompleteMultipartUploadResult><Location>" +
                    S3Xml.xml(location(exchange, bucketName, key)) + "</Location><Bucket>" + S3Xml.xml(bucketName) +
                    "</Bucket><Key>" + S3Xml.xml(key) + "</Key><ETag>&quot;" + meta.etag() +
                    "&quot;</ETag></CompleteMultipartUploadResult>");
            }
            case "DELETE" -> {
                S3Request.requireEmptyBody(exchange, hash);
                multipart.abort(id, bucketName, key);
                exchange.sendResponseHeaders(204, -1);
            }
            case "GET" -> {
                S3Request.requireEmptyBody(exchange, hash);
                listParts(exchange, id, key, query, bucketName);
            }
            default -> S3Request.unsupported("Multipart operation");
        }
    }

    private String location(HttpExchange exchange, String bucket, String key) {
        String host = SigV4.single(exchange.getRequestHeaders(), "host");
        if (host == null) throw new StoreException(400, "InvalidRequest", "Missing Host header");
        String scheme = exchange instanceof HttpsExchange ? "https" : "http";
        return objectLocation(publicEndpoint, host, scheme, exchange.getRequestURI().getRawPath(), bucket, key);
    }

    static String objectLocation(URI endpoint, String host, String scheme, String rawPath,
                                 String bucket, String key) {
        if (endpoint != null)
            return endpoint + "/" + bucket + "/" + SigV4.encode(key, true);
        return URI.create(scheme + "://" + host + rawPath).toASCIIString();
    }

    private void listParts(HttpExchange exchange, String id, String key, Map<String, String> query,
                           String bucketName) throws IOException {
        int marker = S3Request.boundedNumber(query.get("part-number-marker"), 0, 10000, 0);
        int maxParts = S3Request.boundedNumber(query.get("max-parts"), 1, 1000, 1000);
        MultipartStorage.PartPage page = multipart.listParts(id, bucketName, key, marker, maxParts);
        StringBuilder body = new StringBuilder("<ListPartsResult><Bucket>").append(S3Xml.xml(bucketName))
            .append("</Bucket><Key>").append(S3Xml.xml(key)).append("</Key><UploadId>").append(S3Xml.xml(id))
            .append("</UploadId><PartNumberMarker>").append(marker)
            .append("</PartNumberMarker><NextPartNumberMarker>").append(page.nextMarker())
            .append("</NextPartNumberMarker><MaxParts>").append(maxParts)
            .append("</MaxParts><IsTruncated>").append(page.truncated()).append("</IsTruncated>");
        for (MultipartStorage.PartInfo part : page.parts()) {
            body.append("<Part><PartNumber>").append(part.number()).append("</PartNumber><LastModified>")
                .append(Instant.ofEpochMilli(part.modified())).append("</LastModified><ETag>&quot;")
                .append(part.etag()).append("&quot;</ETag><Size>").append(part.length()).append("</Size></Part>");
        }
        S3Xml.sendXml(exchange, 200, body.append("</ListPartsResult>").toString());
    }

    void listUploads(HttpExchange exchange, Map<String, String> query, String bucketName) throws IOException {
        String prefix = query.getOrDefault("prefix", "");
        String marker = query.getOrDefault("key-marker", "");
        String uploadMarker = query.getOrDefault("upload-id-marker", "");
        if (!uploadMarker.isEmpty() && marker.isEmpty())
            throw new StoreException(400, "InvalidArgument", "Upload ID marker requires a key marker");
        int maximum = S3Request.boundedNumber(query.get("max-uploads"), 1, 1000, 1000);
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
        StringBuilder body = new StringBuilder("<ListMultipartUploadsResult><Bucket>").append(S3Xml.xml(bucketName))
            .append("</Bucket><KeyMarker>").append(S3Xml.xml(marker)).append("</KeyMarker><UploadIdMarker>")
            .append(S3Xml.xml(uploadMarker)).append("</UploadIdMarker><MaxUploads>").append(maximum)
            .append("</MaxUploads><IsTruncated>").append(truncated).append("</IsTruncated>");
        if (truncated) {
            MultipartStorage.UploadInfo last = page.getLast();
            body.append("<NextKeyMarker>").append(S3Xml.xml(last.key())).append("</NextKeyMarker><NextUploadIdMarker>")
                .append(S3Xml.xml(last.id())).append("</NextUploadIdMarker>");
        }
        for (MultipartStorage.UploadInfo upload : page) {
            body.append("<Upload><Key>").append(S3Xml.xml(upload.key())).append("</Key><UploadId>")
                .append(S3Xml.xml(upload.id())).append("</UploadId><Initiated>")
                .append(Instant.ofEpochMilli(upload.created())).append("</Initiated></Upload>");
        }
        S3Xml.sendXml(exchange, 200, body.append("</ListMultipartUploadsResult>").toString());
    }
}
