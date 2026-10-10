package cloud.lunarsky.objectstore.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.w3c.dom.Element;

/** A synchronous, path-style S3 client. Instances are safe to share between threads. */
public final class ObjectStorageClient implements AutoCloseable {
    private static final int MAX_XML = 4 * 1024 * 1024;
    private static final int MAX_ERROR = 64 * 1024;
    private static final String EMPTY_HASH = SigV4.hash(new byte[0]);
    private final URI endpoint;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final Duration timeout;
    private final Clock clock;
    private final HttpClient http;
    private final Set<String> observedOperations = ConcurrentHashMap.newKeySet();

    ObjectStorageClient(URI endpoint, String region, String accessKey, String secretKey,
                        Duration timeout, Clock clock) {
        this.endpoint = endpoint;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.timeout = timeout;
        this.clock = clock;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(timeout).version(HttpClient.Version.HTTP_1_1).build();
    }

    /** Returns one open object stream. The caller must close the returned result. */
    public ObjectData getObject(String bucket, String key) throws IOException {
        HttpResponse<InputStream> response = send("GET", objectPath(bucket, key), Map.of(), Map.of(),
            HttpRequest.BodyPublishers.noBody(), EMPTY_HASH);
        if (!successful(response)) {
            try (InputStream ignored = response.body()) { throw failure(response); }
        }
        observedOperations.add("GetObject");
        return new ObjectData(response.body(), length(response), response.headers().firstValue("content-type").orElse(null),
            response.headers().firstValue("etag").orElse(null));
    }

    /** Returns object headers without downloading content. */
    public ObjectMetadata headObject(String bucket, String key) throws IOException {
        HttpResponse<InputStream> response = send("HEAD", objectPath(bucket, key), Map.of(), Map.of(),
            HttpRequest.BodyPublishers.noBody(), EMPTY_HASH);
        try (InputStream ignored = response.body()) {
            if (!successful(response)) throw failure(response);
            observedOperations.add("HeadObject");
            return new ObjectMetadata(length(response), response.headers().firstValue("content-type").orElse(null),
                response.headers().firstValue("etag").orElse(null));
        }
    }

    /** Uploads an immutable byte array as one object. No automatic write retry is performed. */
    public void putObject(String bucket, String key, byte[] content, String contentType) throws IOException {
        Objects.requireNonNull(content, "content");
        put(bucket, key, HttpRequest.BodyPublishers.ofByteArray(content), SigV4.hash(content), contentType);
    }

    /** Uploads a file. Do not change the file between hashing and completion of the upload. */
    public void putObject(String bucket, String key, Path file, String contentType) throws IOException {
        Objects.requireNonNull(file, "file");
        String hash;
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) >= 0; ) digest.update(buffer, 0, count);
            hash = java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        put(bucket, key, HttpRequest.BodyPublishers.ofFile(file), hash, contentType);
    }

    private void put(String bucket, String key, HttpRequest.BodyPublisher body, String hash,
                     String contentType) throws IOException {
        Map<String, String> headers = contentType == null ? Map.of() : Map.of("content-type", contentType);
        HttpResponse<InputStream> response = send("PUT", objectPath(bucket, key), Map.of(), headers, body, hash);
        try (InputStream ignored = response.body()) {
            if (!successful(response)) throw failure(response);
            observedOperations.add("PutObject");
        }
    }

    /** Deletes the current object. A versioned backend may create a delete marker. */
    public void deleteObject(String bucket, String key) throws IOException {
        HttpResponse<InputStream> response = send("DELETE", objectPath(bucket, key), Map.of(), Map.of(),
            HttpRequest.BodyPublishers.noBody(), EMPTY_HASH);
        try (InputStream ignored = response.body()) {
            if (!successful(response)) throw failure(response);
            observedOperations.add("DeleteObject");
        }
    }

    /** Lists one page of objects using S3 ListObjectsV2. Pass the returned token to get the next page. */
    public ObjectPage listObjects(String bucket, String prefix, String continuationToken, int maxKeys)
        throws IOException {
        if (maxKeys < 1 || maxKeys > 1000) throw new IllegalArgumentException("maxKeys must be 1..1000");
        Map<String, String> query = new HashMap<>();
        query.put("list-type", "2");
        query.put("encoding-type", "url");
        query.put("max-keys", Integer.toString(maxKeys));
        if (prefix != null) query.put("prefix", prefix);
        if (continuationToken != null) query.put("continuation-token", continuationToken);
        Element root = xml("GET", bucketPath(bucket), query);
        requireRoot(root, "ListBucketResult");
        observedOperations.add("ListObjectsV2");
        List<ObjectEntry> entries = new ArrayList<>();
        var nodes = Xml.descendants(root, "Contents");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element entry = (Element) nodes.item(i);
            String key = Xml.text(entry, "Key");
            String size = Xml.text(entry, "Size");
            if (key == null || size == null) throw new ProtocolException("Incomplete object listing");
            try {
                entries.add(new ObjectEntry(URLDecoder.decode(key.replace("+", "%2B"), StandardCharsets.UTF_8),
                    Long.parseLong(size), Xml.text(entry, "ETag")));
            } catch (IllegalArgumentException e) { throw new ProtocolException("Invalid object listing entry", e); }
        }
        String next = Xml.text(root, "NextContinuationToken");
        if ("true".equalsIgnoreCase(Xml.text(root, "IsTruncated")) && (next == null || next.isEmpty()))
            throw new ProtocolException("Truncated listing has no continuation token");
        return new ObjectPage(entries, next);
    }

    /** Starts a standard S3 multipart upload. Keep the returned upload ID to resume or abort later. */
    public MultipartUpload createMultipartUpload(String bucket, String key, String contentType) throws IOException {
        String path = objectPath(bucket, key);
        Map<String, String> headers = contentType == null ? Map.of() : Map.of("content-type", contentType);
        HttpResponse<InputStream> response = send("POST", path, Map.of("uploads", ""), headers,
            HttpRequest.BodyPublishers.noBody(), EMPTY_HASH);
        Element root = responseXml(response, "InitiateMultipartUploadResult");
        String id = Xml.text(root, "UploadId");
        if (id == null || id.isBlank()) throw new ProtocolException("Multipart initiation has no upload ID");
        observedOperations.add("CreateMultipartUpload");
        return new MultipartUpload(bucket, key, id);
    }

    /** Uploads one part. The ETag in the result must be supplied when completing the upload. */
    public Part uploadPart(MultipartUpload upload, int partNumber, byte[] content) throws IOException {
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(content, "content");
        checkPartNumber(partNumber);
        HttpResponse<InputStream> response = send("PUT", objectPath(upload.bucket(), upload.key()),
            Map.of("uploadId", upload.uploadId(), "partNumber", Integer.toString(partNumber)), Map.of(),
            HttpRequest.BodyPublishers.ofByteArray(content), SigV4.hash(content));
        try (InputStream ignored = response.body()) {
            if (!successful(response)) throw failure(response);
            String etag = response.headers().firstValue("etag").orElse(null);
            if (etag == null || etag.isBlank()) throw new ProtocolException("Part upload has no ETag");
            observedOperations.add("UploadPart");
            return new Part(partNumber, etag, content.length);
        }
    }

    /**
     * Uploads an immutable file as sequential parts without completing it. The caller owns the
     * upload session and can retry, list parts, complete, or abort it after any failure.
     * Each part is held in memory once; partSize must be 5 to 128 MiB.
     */
    public List<Part> uploadFileParts(MultipartUpload upload, Path file, int partSize) throws IOException {
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(file, "file");
        if (partSize < 5 * 1024 * 1024 || partSize > 128 * 1024 * 1024)
            throw new IllegalArgumentException("partSize must be 5..128 MiB");
        long size = Files.size(file);
        if (size < 1 || (size + partSize - 1L) / partSize > 10000)
            throw new IllegalArgumentException("file requires 1..10000 parts");
        List<Part> parts = new ArrayList<>();
        try (InputStream input = Files.newInputStream(file)) {
            long remaining = size;
            for (int number = 1; remaining > 0; number++) {
                int length = (int) Math.min(remaining, partSize);
                byte[] bytes = input.readNBytes(length);
                if (bytes.length != length) throw new IOException("File changed during multipart upload");
                parts.add(uploadPart(upload, number, bytes));
                remaining -= length;
            }
            if (input.read() != -1) throw new IOException("File changed during multipart upload");
        }
        return List.copyOf(parts);
    }

    /** Lists one page of uploaded parts for resume or verification. */
    public PartPage listParts(MultipartUpload upload, int partNumberMarker, int maxParts) throws IOException {
        Objects.requireNonNull(upload, "upload");
        if (partNumberMarker < 0 || partNumberMarker > 10000 || maxParts < 1 || maxParts > 1000)
            throw new IllegalArgumentException("invalid part marker or page size");
        Element root = xml("GET", objectPath(upload.bucket(), upload.key()),
            Map.of("uploadId", upload.uploadId(), "part-number-marker", Integer.toString(partNumberMarker),
                "max-parts", Integer.toString(maxParts)));
        requireRoot(root, "ListPartsResult");
        observedOperations.add("ListParts");
        List<Part> parts = new ArrayList<>();
        var nodes = Xml.descendants(root, "Part");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element entry = (Element) nodes.item(i);
            try {
                parts.add(new Part(Integer.parseInt(requiredText(entry, "PartNumber")),
                    requiredText(entry, "ETag"), Long.parseLong(requiredText(entry, "Size"))));
            } catch (NumberFormatException e) { throw new ProtocolException("Invalid part listing entry", e); }
        }
        boolean truncated = Boolean.parseBoolean(Xml.text(root, "IsTruncated"));
        int next = partNumberMarker;
        if (truncated) {
            try { next = Integer.parseInt(requiredText(root, "NextPartNumberMarker")); }
            catch (NumberFormatException e) { throw new ProtocolException("Invalid next part marker", e); }
            if (next <= partNumberMarker) throw new ProtocolException("Part listing did not advance");
        }
        return new PartPage(parts, truncated, next);
    }

    /** Completes the upload using the exact part numbers and ETags returned by the service. */
    public String completeMultipartUpload(MultipartUpload upload, List<Part> parts) throws IOException {
        Objects.requireNonNull(upload, "upload");
        Objects.requireNonNull(parts, "parts");
        if (parts.isEmpty() || parts.size() > 10000) throw new IllegalArgumentException("1..10000 parts required");
        List<Part> ordered = new ArrayList<>(parts);
        ordered.sort(Comparator.comparingInt(Part::number));
        StringBuilder xml = new StringBuilder("<CompleteMultipartUpload>");
        int previous = 0;
        for (Part part : ordered) {
            Objects.requireNonNull(part, "part");
            checkPartNumber(part.number());
            if (part.number() == previous) throw new IllegalArgumentException("duplicate part number");
            previous = part.number();
            xml.append("<Part><PartNumber>").append(part.number()).append("</PartNumber><ETag>")
                .append(Xml.escape(part.etag())).append("</ETag></Part>");
        }
        byte[] bytes = xml.append("</CompleteMultipartUpload>").toString().getBytes(StandardCharsets.UTF_8);
        HttpResponse<InputStream> response = send("POST", objectPath(upload.bucket(), upload.key()),
            Map.of("uploadId", upload.uploadId()), Map.of("content-type", "application/xml"),
            HttpRequest.BodyPublishers.ofByteArray(bytes), SigV4.hash(bytes));
        Element root = responseXml(response, "CompleteMultipartUploadResult");
        observedOperations.add("CompleteMultipartUpload");
        return requiredText(root, "ETag");
    }

    /** Aborts an unfinished upload. A completed upload cannot be aborted. */
    public void abortMultipartUpload(MultipartUpload upload) throws IOException {
        Objects.requireNonNull(upload, "upload");
        HttpResponse<InputStream> response = send("DELETE", objectPath(upload.bucket(), upload.key()),
            Map.of("uploadId", upload.uploadId()), Map.of(), HttpRequest.BodyPublishers.noBody(), EMPTY_HASH);
        try (InputStream ignored = response.body()) {
            if (!successful(response)) throw failure(response);
            observedOperations.add("AbortMultipartUpload");
        }
    }

    /** Lists one page of unfinished uploads in a bucket. */
    public MultipartUploadPage listMultipartUploads(String bucket, String prefix, String keyMarker,
                                                    String uploadIdMarker, int maxUploads) throws IOException {
        if (maxUploads < 1 || maxUploads > 1000) throw new IllegalArgumentException("maxUploads must be 1..1000");
        if (uploadIdMarker != null && keyMarker == null)
            throw new IllegalArgumentException("upload ID marker requires key marker");
        Map<String, String> query = new HashMap<>();
        query.put("uploads", "");
        query.put("max-uploads", Integer.toString(maxUploads));
        if (prefix != null) query.put("prefix", prefix);
        if (keyMarker != null) query.put("key-marker", keyMarker);
        if (uploadIdMarker != null) query.put("upload-id-marker", uploadIdMarker);
        Element root = xml("GET", bucketPath(bucket), query);
        requireRoot(root, "ListMultipartUploadsResult");
        observedOperations.add("ListMultipartUploads");
        List<MultipartUpload> uploads = new ArrayList<>();
        var nodes = Xml.descendants(root, "Upload");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element entry = (Element) nodes.item(i);
            uploads.add(new MultipartUpload(bucket, requiredText(entry, "Key"), requiredText(entry, "UploadId")));
        }
        boolean truncated = Boolean.parseBoolean(Xml.text(root, "IsTruncated"));
        String nextKey = Xml.text(root, "NextKeyMarker");
        String nextId = Xml.text(root, "NextUploadIdMarker");
        if (truncated && (nextKey == null || nextId == null))
            throw new ProtocolException("Truncated upload listing has no markers");
        return new MultipartUploadPage(uploads, truncated, nextKey, nextId);
    }

    private static String requiredText(Element element, String name) throws ProtocolException {
        String value = Xml.text(element, name);
        if (value == null || value.isBlank()) throw new ProtocolException("Multipart response missing " + name);
        return value;
    }

    private static void checkPartNumber(int number) {
        if (number < 1 || number > 10000) throw new IllegalArgumentException("part number must be 1..10000");
    }

    /** Gets a bucket ACL. The backend may reject ACL operations when ACLs are disabled. */
    public AclPolicy getBucketAcl(String bucket) throws IOException {
        AclPolicy policy = readAcl(bucketPath(bucket));
        observedOperations.add("GetBucketAcl");
        return policy;
    }

    /** Gets an object ACL. This does not calculate permissions from policies or other grants. */
    public AclPolicy getObjectAcl(String bucket, String key) throws IOException {
        AclPolicy policy = readAcl(objectPath(bucket, key));
        observedOperations.add("GetObjectAcl");
        return policy;
    }

    /** Replaces a bucket ACL. No automatic retry is performed. */
    public void putBucketAcl(String bucket, AclPolicy policy) throws IOException {
        writeAcl(bucketPath(bucket), policy);
        observedOperations.add("PutBucketAcl");
    }

    /** Replaces an object ACL. No automatic retry is performed. */
    public void putObjectAcl(String bucket, String key, AclPolicy policy) throws IOException {
        writeAcl(objectPath(bucket, key), policy);
        observedOperations.add("PutObjectAcl");
    }

    private AclPolicy readAcl(String path) throws IOException {
        Element root = xml("GET", path, Map.of("acl", ""));
        requireRoot(root, "AccessControlPolicy");
        Element owner = Xml.child(root, "Owner");
        String ownerId = owner == null ? null : Xml.text(owner, "ID");
        if (ownerId == null || ownerId.isBlank()) throw new ProtocolException("ACL response has no owner ID");
        List<AclPolicy.Grant> grants = new ArrayList<>();
        var nodes = Xml.descendants(root, "Grant");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element grant = (Element) nodes.item(i);
            Element grantee = Xml.child(grant, "Grantee");
            if (grantee == null) throw new ProtocolException("ACL grant has no grantee");
            String type = grantee.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "type");
            if (type.isEmpty()) type = grantee.getAttribute("xsi:type");
            AclPolicy.GranteeType kind = switch (type) {
                case "CanonicalUser" -> AclPolicy.GranteeType.CANONICAL_USER;
                case "Group" -> AclPolicy.GranteeType.GROUP;
                default -> throw new ProtocolException("Unsupported ACL grantee type");
            };
            String id = Xml.text(grantee, kind == AclPolicy.GranteeType.GROUP ? "URI" : "ID");
            String permission = Xml.text(grant, "Permission");
            if (id == null || permission == null) throw new ProtocolException("Incomplete ACL grant");
            try { grants.add(new AclPolicy.Grant(kind, id, AclPolicy.Permission.valueOf(permission))); }
            catch (IllegalArgumentException e) { throw new ProtocolException("Unsupported ACL permission", e); }
        }
        return new AclPolicy(ownerId, grants);
    }

    private void writeAcl(String path, AclPolicy policy) throws IOException {
        Objects.requireNonNull(policy, "policy");
        StringBuilder xml = new StringBuilder("<AccessControlPolicy xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\" ")
            .append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><Owner><ID>")
            .append(Xml.escape(policy.ownerId())).append("</ID></Owner><AccessControlList>");
        for (AclPolicy.Grant grant : policy.grants()) {
            boolean group = grant.type() == AclPolicy.GranteeType.GROUP;
            xml.append("<Grant><Grantee xsi:type=\"").append(group ? "Group" : "CanonicalUser")
                .append("\"><").append(group ? "URI" : "ID").append('>')
                .append(Xml.escape(grant.grantee())).append("</").append(group ? "URI" : "ID")
                .append("></Grantee><Permission>").append(grant.permission()).append("</Permission></Grant>");
        }
        xml.append("</AccessControlList></AccessControlPolicy>");
        byte[] bytes = xml.toString().getBytes(StandardCharsets.UTF_8);
        String md5;
        try { md5 = Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        HttpResponse<InputStream> response = send("PUT", path, Map.of("acl", ""),
            Map.of("content-type", "application/xml", "content-md5", md5),
            HttpRequest.BodyPublishers.ofByteArray(bytes), SigV4.hash(bytes));
        try (InputStream ignored = response.body()) {
            if (!successful(response)) throw failure(response);
        }
    }

    /**
     * Reads ObjectStore's signed manifest when present. Other S3 endpoints report only operations
     * observed succeeding on this client. A denied or missing manifest does not imply a feature is
     * unsupported. Service identity from /health is self-reported, not cryptographic attestation.
     */
    public Capabilities getCapabilities() throws IOException {
        HttpResponse<InputStream> response = send("GET", "/_objectstore/capabilities", Map.of(), Map.of(),
            HttpRequest.BodyPublishers.noBody(), EMPTY_HASH);
        ProtocolException invalidManifest = null;
        try (InputStream body = response.body()) {
            if (successful(response)) {
                try { return withObserved(parseManifest(readLimited(body, 64 * 1024))); }
                catch (ProtocolException e) { invalidManifest = e; }
            }
        }
        boolean objectStore = probeHealth();
        if (invalidManifest != null && objectStore) throw invalidManifest;
        return withObserved(new Capabilities(objectStore ? Capabilities.ServiceKind.OBJECTSTORE :
            Capabilities.ServiceKind.UNKNOWN_S3, Map.of()));
    }

    /**
     * Opts into read-only probes on a known bucket and, optionally, an existing object key.
     * Successful calls establish support for this principal. S3 error responses leave support
     * UNKNOWN; transport and malformed-response errors are still reported to the caller.
     */
    public Capabilities probeReadOnlyCapabilities(String bucket, String existingObjectKey) throws IOException {
        Capabilities base = getCapabilities();
        probe(base, "ListObjectsV2", () -> listObjects(bucket, null, null, 1));
        probe(base, "ListMultipartUploads", () -> listMultipartUploads(bucket, null, null, null, 1));
        probe(base, "GetBucketAcl", () -> getBucketAcl(bucket));
        if (existingObjectKey != null) {
            probe(base, "HeadObject", () -> headObject(bucket, existingObjectKey));
            probe(base, "GetObjectAcl", () -> getObjectAcl(bucket, existingObjectKey));
        }
        return withObserved(base);
    }

    private void probe(Capabilities base, String operation, Probe call) throws IOException {
        if (base.support(operation) == Capabilities.Support.UNSUPPORTED) return;
        try { call.run(); }
        catch (ObjectStorageException ignored) { }
    }

    @FunctionalInterface private interface Probe { void run() throws IOException; }

    private Capabilities withObserved(Capabilities base) {
        Map<String, Capabilities.Support> operations = new HashMap<>(base.operations());
        observedOperations.forEach(name -> operations.put(name, Capabilities.Support.SUPPORTED));
        return new Capabilities(base.service(), operations, base.limits(), base.serviceVersion(),
            base.storageMode(), base.completeOperationInventory());
    }

    private static Capabilities parseManifest(byte[] bytes) throws ProtocolException {
        Map<String, Object> document = Json.object(Json.parse(bytes), "capability manifest");
        if (Json.integer(document.get("schemaVersion"), "schemaVersion") != 1 ||
            !"lunarsky-objectstore".equals(Json.string(document.get("service"), "service")))
            throw new ProtocolException("Unsupported capability manifest");
        String version = Json.string(document.get("serviceVersion"), "serviceVersion");
        String mode = Json.string(document.get("storageMode"), "storageMode");
        Map<String, Capabilities.Support> operations = new HashMap<>();
        for (Object value : Json.array(document.get("operations"), "operations")) {
            String name = Json.string(value, "operation name");
            if (operations.put(name, Capabilities.Support.SUPPORTED) != null)
                throw new ProtocolException("Duplicate capability operation");
        }
        Map<String, Object> rawLimits = Json.object(document.get("limits"), "limits");
        Map<String, Long> limits = new HashMap<>();
        for (var entry : rawLimits.entrySet()) {
            long value = Json.integer(entry.getValue(), "limit " + entry.getKey());
            if (value < 0) throw new ProtocolException("Negative capability limit");
            limits.put(entry.getKey(), value);
        }
        for (String required : List.of("maxObjectBytes", "maxTotalBytes", "maxParts"))
            if (!limits.containsKey(required)) throw new ProtocolException("Missing capability limit " + required);
        return new Capabilities(Capabilities.ServiceKind.OBJECTSTORE, operations, limits, version, mode, true);
    }

    private boolean probeHealth() throws IOException {
        URI uri = URI.create(origin() + "/health");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        HttpResponse<InputStream> response = execute(request);
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) return false;
            String value = new String(readLimited(body, 1024), StandardCharsets.UTF_8);
            return value.matches("(?s)\\s*\\{\\s*\"status\"\\s*:\\s*\"ok\"\\s*,\\s*\"service\"\\s*:\\s*\"lunarsky-objectstore\"\\s*}\\s*");
        }
    }

    private Element xml(String method, String path, Map<String, String> query) throws IOException {
        HttpResponse<InputStream> response = send(method, path, query, Map.of(),
            HttpRequest.BodyPublishers.noBody(), EMPTY_HASH);
        return responseXml(response, null);
    }

    private static Element responseXml(HttpResponse<InputStream> response, String expectedRoot) throws IOException {
        try (InputStream body = response.body()) {
            if (!successful(response)) throw failure(response);
            Element root = Xml.parse(readLimited(body, MAX_XML)).getDocumentElement();
            if ("Error".equals(root.getLocalName())) {
                String code = Xml.text(root, "Code");
                throw new ObjectStorageException(response.statusCode(), code,
                    response.headers().firstValue("x-amz-request-id").orElse(null));
            }
            if (expectedRoot != null) requireRoot(root, expectedRoot);
            return root;
        }
    }

    private HttpResponse<InputStream> send(String method, String path, Map<String, String> query,
                                            Map<String, String> extra, HttpRequest.BodyPublisher body,
                                            String payloadHash) throws IOException {
        String queryString = SigV4.query(query);
        URI uri = URI.create(origin() + path + (queryString.isEmpty() ? "" : "?" + queryString));
        Instant now = clock.instant();
        Map<String, String> headers = new HashMap<>(extra);
        headers.put("host", uri.getRawAuthority().toLowerCase(Locale.ROOT));
        headers.put("x-amz-date", SigV4.timestamp(now));
        headers.put("x-amz-content-sha256", payloadHash);
        String authorization = SigV4.authorization(method, uri, headers, payloadHash, now,
            region, accessKey, secretKey);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach((name, value) -> {
            if (!"host".equals(name)) request.header(name, value);
        });
        request.header("Authorization", authorization);
        return execute(request.method(method, body).build());
    }

    private HttpResponse<InputStream> execute(HttpRequest request) throws IOException {
        try { return http.send(request, HttpResponse.BodyHandlers.ofInputStream()); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Storage request interrupted", e);
        } catch (IOException e) { throw new TransportException(e); }
    }

    private static boolean successful(HttpResponse<?> response) {
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private static ObjectStorageException failure(HttpResponse<InputStream> response) throws IOException {
        String code = null;
        String requestId = response.headers().firstValue("x-amz-request-id").orElse(null);
        try {
            byte[] bytes = readLimited(response.body(), MAX_ERROR);
            if (bytes.length > 0) {
                Element root = Xml.parse(bytes).getDocumentElement();
                if ("Error".equals(root.getLocalName())) {
                    code = Xml.text(root, "Code");
                    if (requestId == null) requestId = Xml.text(root, "RequestId");
                }
            }
        } catch (IOException ignored) {
        }
        return new ObjectStorageException(response.statusCode(), code, requestId);
    }

    private static byte[] readLimited(InputStream stream, int limit) throws IOException {
        byte[] bytes = stream.readNBytes(limit + 1);
        if (bytes.length > limit) throw new ProtocolException("Storage response exceeds size limit");
        return bytes;
    }

    private static long length(HttpResponse<?> response) {
        return response.headers().firstValueAsLong("content-length").orElse(-1);
    }

    private static void requireRoot(Element root, String name) throws IOException {
        if (!name.equals(root.getLocalName())) throw new ProtocolException("Unexpected storage XML response");
    }

    private String origin() {
        return endpoint.getScheme() + "://" + endpoint.getRawAuthority();
    }

    private static String bucketPath(String bucket) {
        validateBucket(bucket);
        return "/" + SigV4.encode(bucket, false);
    }

    private static String objectPath(String bucket, String key) {
        if (key == null || key.isEmpty() || key.indexOf('\0') >= 0 ||
            key.getBytes(StandardCharsets.UTF_8).length > 1024)
            throw new IllegalArgumentException("object key must be 1..1024 UTF-8 bytes without NUL");
        return bucketPath(bucket) + "/" + SigV4.encode(key, true);
    }

    private static void validateBucket(String bucket) {
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]"))
            throw new IllegalArgumentException("bucket must be a 3..63 character DNS-style name");
    }

    /** Closes the underlying HTTP client. Do not use this instance afterward. */
    @Override public void close() { http.close(); }

    /** An open response stream and selected object headers. Close this result after reading. */
    public record ObjectData(InputStream body, long length, String contentType, String etag)
        implements AutoCloseable {
        @Override public void close() throws IOException { body.close(); }
    }

    /** Headers returned by a HEAD request. A negative length means it was not supplied. */
    public record ObjectMetadata(long length, String contentType, String etag) { }

    /** A listed object. */
    public record ObjectEntry(String key, long size, String etag) { }

    /** One listing page. A null next token means no next page was reported. */
    public record ObjectPage(List<ObjectEntry> objects, String nextContinuationToken) {
        public ObjectPage { objects = List.copyOf(objects); }
    }

    /** An unfinished multipart upload. Persist all three fields if resuming in a later process. */
    public record MultipartUpload(String bucket, String key, String uploadId) {
        public MultipartUpload {
            validateBucket(bucket);
            if (key == null || key.isEmpty()) throw new IllegalArgumentException("key is required");
            if (uploadId == null || uploadId.isBlank()) throw new IllegalArgumentException("upload ID is required");
        }
    }

    /** A successfully uploaded part, including the service-provided ETag. */
    public record Part(int number, String etag, long size) {
        public Part {
            checkPartNumber(number);
            if (etag == null || etag.isBlank()) throw new IllegalArgumentException("ETag is required");
            if (size < 0) throw new IllegalArgumentException("part size must not be negative");
        }
    }

    /** A page of uploaded parts. Pass nextPartNumberMarker to the next call when truncated. */
    public record PartPage(List<Part> parts, boolean truncated, int nextPartNumberMarker) {
        public PartPage { parts = List.copyOf(parts); }
    }

    /** A page of unfinished uploads. Pass both next markers to the next call when truncated. */
    public record MultipartUploadPage(List<MultipartUpload> uploads, boolean truncated,
                                      String nextKeyMarker, String nextUploadIdMarker) {
        public MultipartUploadPage { uploads = List.copyOf(uploads); }
    }
}
