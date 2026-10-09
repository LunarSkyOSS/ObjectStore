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
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public final class Main {
    private final ObjectStorage store;
    private final SigV4 authentication;
    private final String bucket;
    private final MultipartStorage multipart;
    private final Semaphore slots = new Semaphore(16);

    Main(DiskStore store, SigV4 authentication, String bucket) throws IOException {
        this(store, new MultipartStore(store), authentication, bucket);
    }

    Main(ObjectStorage store, MultipartStorage multipart, SigV4 authentication, String bucket) {
        this.store = store; this.multipart = multipart;
        this.authentication = authentication; this.bucket = bucket;
    }

    void handle(HttpExchange exchange) throws IOException {
        boolean admitted = slots.tryAcquire();
        String requestId = UUID.randomUUID().toString();
        exchange.getResponseHeaders().set("x-amz-request-id", requestId);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        try {
            if (!admitted) throw new StoreException(503, "SlowDown", "Too many concurrent requests");
            if (exchange.getRequestURI().getRawPath().equals("/health") && exchange.getRequestMethod().equals("GET")) {
                byte[] body = "{\"status\":\"ok\",\"service\":\"lunarsky-objectstore\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            if (exchange.getRequestURI().getRawPath().equals("/ready") && exchange.getRequestMethod().equals("GET")) {
                boolean ready = store.ready();
                byte[] body = (ready ? "ready" : "unavailable").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(ready ? 200 : 503, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            String hash = authentication.verify(exchange.getRequestMethod(), exchange.getRequestURI(), exchange.getRequestHeaders());
            String path = SigV4.decode(exchange.getRequestURI().getRawPath());
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            if (path.equals("/" + bucket) || path.equals("/" + bucket + "/")) {
                if (!exchange.getRequestMethod().equals("GET") || !"2".equals(query.get("list-type")) ||
                    !query.keySet().stream().allMatch(java.util.Set.of("list-type", "prefix", "delimiter", "max-keys",
                        "continuation-token", "start-after", "encoding-type", "x-id")::contains) ||
                    (query.containsKey("x-id") && !"ListObjectsV2".equals(query.get("x-id"))))
                    unsupported("Bucket operation");
                requireEmptyBody(exchange, hash);
                listObjects(exchange, query);
                return;
            }
            String prefix = "/" + bucket + "/";
            if (!path.startsWith(prefix)) throw new StoreException(404, "NoSuchBucket", "Bucket not found");
            String key = path.substring(prefix.length());
            if (key.isEmpty() || key.getBytes(StandardCharsets.UTF_8).length > 1024 || key.indexOf('\0') >= 0)
                throw new StoreException(400, "InvalidArgument", "Invalid object key");
            String method = exchange.getRequestMethod();
            boolean multipartRequest = multipartRequest(method, query);
            if (!multipartRequest && !query.isEmpty() && !(query.size() == 1 &&
                ("PutObject".equals(query.get("x-id")) || "GetObject".equals(query.get("x-id")) ||
                 "HeadObject".equals(query.get("x-id")) || "DeleteObject".equals(query.get("x-id")))))
                unsupported("Query operation");
            var headers = exchange.getRequestHeaders();
            for (String name : headers.keySet()) {
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.startsWith("x-amz-") && !java.util.Set.of("x-amz-date", "x-amz-content-sha256",
                    "x-amz-checksum-sha256", "x-amz-sdk-checksum-algorithm", "x-amz-user-agent").contains(lower))
                    unsupported("Amazon header");
                if (lower.startsWith("x-amz-meta-") || lower.startsWith("x-amz-server-side-") ||
                    lower.startsWith("x-amz-copy-") || lower.startsWith("x-amz-acl") ||
                    lower.startsWith("x-amz-grant") || lower.startsWith("x-amz-tagging") ||
                    lower.equals("content-md5")) unsupported("Object metadata, encryption, ACL, copy, tagging or MD5 header");
                if (lower.startsWith("x-amz-checksum-") && !lower.equals("x-amz-checksum-sha256"))
                    unsupported("Checksum algorithm");
            }
            String algorithm = SigV4.single(headers, "x-amz-sdk-checksum-algorithm");
            if (algorithm != null && !algorithm.equals("SHA256")) unsupported("Checksum algorithm");
            if (multipartRequest) {
                handleMultipart(exchange, method, query, key, hash);
                return;
            }
            if (!method.equals("PUT")) requireEmptyBody(exchange, hash);
            switch (method) {
                case "PUT" -> {
                    String length = SigV4.single(headers, "content-length"), condition = SigV4.single(headers, "if-none-match");
                    if (condition != null && !condition.equals("*")) unsupported("Write condition");
                    long bytes;
                    try { bytes = length == null ? -1 : Long.parseLong(length); }
                    catch (NumberFormatException e) { throw new StoreException(400, "InvalidArgument", "Invalid Content-Length"); }
                    if (headers.containsKey("content-encoding")) unsupported("Encoded payload");
                    String contentType = contentType(headers);
                    ObjectStorage.Metadata data = store.put(bucket, key, exchange.getRequestBody(), bytes, hash,
                        SigV4.single(headers, "x-amz-checksum-sha256"), condition != null, contentType);
                    exchange.getResponseHeaders().set("ETag", "\"" + data.etag() + "\"");
                    exchange.getResponseHeaders().set("x-amz-checksum-sha256", Base64.getEncoder().encodeToString(data.sha256()));
                    exchange.sendResponseHeaders(200, -1);
                }
                case "GET", "HEAD" -> readObject(exchange, key);
                case "DELETE" -> {
                    if (headers.containsKey("if-none-match")) unsupported("Conditional delete");
                    store.delete(bucket, key);
                    exchange.sendResponseHeaders(204, -1);
                }
                default -> unsupported("HTTP method");
            }
        } catch (StoreException error) { sendError(exchange, error.status, error.code, error.getMessage(), requestId); }
        catch (Exception error) {
            System.err.println("ObjectStore request failed: " + requestId + " " + error.getClass().getSimpleName());
            sendError(exchange, 500, "InternalError", "Storage operation failed", requestId);
        } finally { if (admitted) slots.release(); exchange.close(); }
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
            !hash.equals(SigV4.hex(SigV4.hash(new byte[0]))))
            throw new StoreException(400, "InvalidRequest", "Request must have an empty body");
    }

    private static boolean multipartRequest(String method, Map<String, String> query) {
        if (query.containsKey("uploads"))
            return method.equals("POST") && query.get("uploads").isEmpty() &&
                query.keySet().stream().allMatch(java.util.Set.of("uploads", "x-id")::contains) &&
                (!query.containsKey("x-id") || query.get("x-id").equals("CreateMultipartUpload"));
        if (!query.containsKey("uploadId") ||
            !query.keySet().stream().allMatch(java.util.Set.of("uploadId", "partNumber", "x-id")::contains))
            return false;
        String xId = query.get("x-id");
        if (method.equals("PUT")) return query.containsKey("partNumber") &&
            (xId == null || xId.equals("UploadPart"));
        if (query.containsKey("partNumber")) return false;
        return (method.equals("POST") && (xId == null || xId.equals("CompleteMultipartUpload"))) ||
            (method.equals("DELETE") && (xId == null || xId.equals("AbortMultipartUpload")));
    }

    private void handleMultipart(HttpExchange exchange, String method, Map<String, String> query,
                                 String key, String hash) throws IOException {
        var headers = exchange.getRequestHeaders();
        if (headers.containsKey("content-encoding") || headers.containsKey("if-none-match"))
            unsupported("Multipart request header");
        if (query.containsKey("uploads")) {
            requireEmptyBody(exchange, hash);
            String id = multipart.create(bucket, key, contentType(headers));
            sendXml(exchange, 200, "<InitiateMultipartUploadResult><Bucket>" + xml(bucket) +
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
                long length = contentLength(headers);
                String etag = multipart.putPart(id, bucket, key, number, exchange.getRequestBody(), length,
                    hash, SigV4.single(headers, "x-amz-checksum-sha256"));
                exchange.getResponseHeaders().set("ETag", "\"" + etag + "\"");
                exchange.sendResponseHeaders(200, -1);
            }
            case "POST" -> {
                byte[] body = signedBody(exchange, hash, 65536);
                List<MultipartStorage.Part> parts = completedParts(body);
                var meta = multipart.complete(id, bucket, key, parts);
                sendXml(exchange, 200, "<CompleteMultipartUploadResult><Bucket>" + xml(bucket) +
                    "</Bucket><Key>" + xml(key) + "</Key><ETag>&quot;" + meta.etag() +
                    "&quot;</ETag></CompleteMultipartUploadResult>");
            }
            case "DELETE" -> {
                requireEmptyBody(exchange, hash);
                multipart.abort(id, bucket, key);
                exchange.sendResponseHeaders(204, -1);
            }
            default -> unsupported("Multipart operation");
        }
    }

    private static long contentLength(com.sun.net.httpserver.Headers headers) {
        String text = SigV4.single(headers, "content-length");
        if (text == null) return -1;
        try { return Long.parseLong(text); }
        catch (NumberFormatException e) { throw new StoreException(400, "InvalidArgument", "Invalid Content-Length"); }
    }
    private static byte[] signedBody(HttpExchange exchange, String hash, int limit) throws IOException {
        long length = contentLength(exchange.getRequestHeaders());
        if (length < 0) throw new StoreException(411, "MissingContentLength", "Content-Length is required");
        if (length > limit) throw new StoreException(413, "EntityTooLarge", "Request body is too large");
        byte[] body = exchange.getRequestBody().readNBytes(limit + 1);
        if (body.length != length) throw new StoreException(400, "IncompleteBody", "Body length does not match Content-Length");
        if (!SigV4.hex(SigV4.hash(body)).equals(hash))
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

    private void readObject(HttpExchange exchange, String key) throws IOException {
        var headers = exchange.getRequestHeaders();
        if (headers.containsKey("if-match") || headers.containsKey("if-modified-since") ||
            headers.containsKey("if-unmodified-since")) unsupported("Conditional read");
        try (var object = store.open(bucket, key)) {
            var meta = object.metadata();
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
            response.set("Last-Modified", DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(meta.modified())));
            if (range.partial()) response.set("Content-Range", "bytes " + range.start() + "-" + range.end() + "/" + meta.length());
            int status = range.partial() ? 206 : 200;
            if (exchange.getRequestMethod().equals("HEAD") || range.length() == 0)
                exchange.sendResponseHeaders(status, -1);
            else {
                object.stream().skipNBytes(range.start());
                exchange.sendResponseHeaders(status, range.length());
                byte[] buffer = new byte[65536]; long left = range.length();
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
                start = Math.max(0, size - suffix); end = size - 1;
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

    private void listObjects(HttpExchange exchange, Map<String, String> query) throws IOException {
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
        var page = store.list(bucket, prefix, delimiter, maxKeys, after);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
        xml.append("<Name>").append(xml(bucket)).append("</Name><Prefix>").append(xml(listKey(prefix, encoding))).append("</Prefix>");
        if (!delimiter.isEmpty()) xml.append("<Delimiter>").append(xml(listKey(delimiter, encoding))).append("</Delimiter>");
        if (encoding != null) xml.append("<EncodingType>url</EncodingType>");
        if (query.containsKey("continuation-token")) xml.append("<ContinuationToken>")
            .append(xml(query.get("continuation-token"))).append("</ContinuationToken>");
        if (query.containsKey("start-after")) xml.append("<StartAfter>")
            .append(xml(listKey(query.get("start-after"), encoding))).append("</StartAfter>");
        xml.append("<KeyCount>").append(page.keyCount()).append("</KeyCount><MaxKeys>").append(maxKeys)
            .append("</MaxKeys><IsTruncated>").append(page.truncated()).append("</IsTruncated>");
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
        if (page.truncated()) xml.append("<NextContinuationToken>")
            .append(Base64.getUrlEncoder().withoutPadding().encodeToString(page.nextKey().getBytes(StandardCharsets.UTF_8)))
            .append("</NextContinuationToken>");
        xml.append("</ListBucketResult>");
        byte[] body = xml.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/xml");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
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
            multipart = new UnavailableMultipart();
        } else if (mode.equals("disk")) {
            DiskStore disk = new DiskStore(Path.of(env.getOrDefault("DATA_DIR", "/data")), maxObject, maxTotal);
            store = disk;
            multipart = new MultipartStore(disk);
        } else throw new IllegalArgumentException("Invalid STORE_MODE");
        var app = new Main(store, multipart, new SigV4(access, secret, region, Clock.systemUTC()), bucket);
        int port = Integer.parseInt(env.getOrDefault("PORT", "9000"));
        var server = HttpServer.create(mode.equals("cluster")
            ? new InetSocketAddress(env.getOrDefault("BIND_ADDRESS", "127.0.0.1"), port)
            : new InetSocketAddress(port), 64);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor); server.createContext("/", app::handle);
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
        do { value /= 1024; unit++; } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
}
