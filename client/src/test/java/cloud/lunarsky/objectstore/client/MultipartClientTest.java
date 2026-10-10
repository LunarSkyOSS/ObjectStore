package cloud.lunarsky.objectstore.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class MultipartClientTest {
    public static void main(String[] args) throws Exception {
        AtomicReference<String> completion = new AtomicReference<>();
        AtomicBoolean completionError = new AtomicBoolean();
        List<Integer> sizes = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { handle(exchange, completion, completionError, sizes); }
            finally { exchange.close(); }
        });
        server.start();
        try (ObjectStorageClient client = new ObjectStorageClientBuilder()
            .endpoint(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
            .allowInsecureHttp().credentials("test-key", "test-secret").build()) {
            var upload = client.createMultipartUpload("mybucket", "large file.bin", "application/octet-stream");
            check("u-1".equals(upload.uploadId()), "initiation ID");
            var first = client.uploadPart(upload, 1, "first".getBytes(StandardCharsets.UTF_8));
            var second = client.uploadPart(upload, 2, "second".getBytes(StandardCharsets.UTF_8));
            check(first.number() == 1 && "\"etag-1\"".equals(first.etag()), "part result");
            var page = client.listParts(upload, 0, 1);
            check(page.truncated() && page.nextPartNumberMarker() == 1 && page.parts().size() == 1,
                "first part page");
            page = client.listParts(upload, page.nextPartNumberMarker(), 1);
            check(!page.truncated() && page.parts().getFirst().number() == 2, "second part page");
            var uploads = client.listMultipartUploads("mybucket", "large", null, null, 10);
            check(uploads.uploads().size() == 1 && "u-1".equals(uploads.uploads().getFirst().uploadId()),
                "unfinished upload listing");
            String etag = client.completeMultipartUpload(upload, List.of(second, first));
            check("\"final-etag\"".equals(etag), "completion ETag");
            check(completion.get().indexOf("<PartNumber>1</PartNumber>") <
                completion.get().indexOf("<PartNumber>2</PartNumber>"), "completion part order");
            completionError.set(true);
            try {
                client.completeMultipartUpload(upload, List.of(first));
                throw new AssertionError("Expected embedded S3 error");
            } catch (ObjectStorageException error) {
                check("InvalidPart".equals(error.errorCode()), "HTTP 200 completion error");
            }
            completionError.set(false);
            Path file = Files.createTempFile("objectstore-client-multipart", ".bin");
            try {
                byte[] content = new byte[5 * 1024 * 1024 + 3];
                content[content.length - 1] = 42;
                Files.write(file, content);
                var fileParts = client.uploadFileParts(upload, file, 5 * 1024 * 1024);
                check(fileParts.size() == 2 && fileParts.getLast().size() == 3, "file part slicing");
                check(sizes.contains(5 * 1024 * 1024) && sizes.contains(3), "part request sizes");
            } finally { Files.deleteIfExists(file); }
            client.abortMultipartUpload(upload);
            System.out.println("Multipart client tests passed");
        } finally { server.stop(0); }
    }

    private static void handle(HttpExchange exchange, AtomicReference<String> completion,
                               AtomicBoolean completionError, List<Integer> sizes) throws IOException {
        check(exchange.getRequestHeaders().getFirst("Authorization") != null, "signed multipart request");
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        String method = exchange.getRequestMethod();
        if ("/mybucket".equals(path) && "GET".equals(method) && query.contains("uploads=")) {
            reply(exchange, 200, "<ListMultipartUploadsResult><Upload><Key>large file.bin</Key>" +
                "<UploadId>u-1</UploadId></Upload><IsTruncated>false</IsTruncated></ListMultipartUploadsResult>");
            return;
        }
        check("/mybucket/large%20file.bin".equals(path), "multipart path");
        if ("POST".equals(method) && "uploads=".equals(query)) {
            reply(exchange, 200, "<InitiateMultipartUploadResult><UploadId>u-1" +
                "</UploadId></InitiateMultipartUploadResult>");
        } else if ("PUT".equals(method) && query.contains("partNumber=")) {
            int number = query.contains("partNumber=1") ? 1 : 2;
            byte[] body = exchange.getRequestBody().readAllBytes();
            check(SigV4.hash(body).equals(exchange.getRequestHeaders().getFirst("x-amz-content-sha256")),
                "part hash");
            sizes.add(body.length);
            exchange.getResponseHeaders().set("ETag", "\"etag-" + number + "\"");
            reply(exchange, 200, "");
        } else if ("GET".equals(method) && query.contains("uploadId=")) {
            boolean first = query.contains("part-number-marker=0");
            reply(exchange, 200, "<ListPartsResult><Part><PartNumber>" + (first ? 1 : 2) +
                "</PartNumber><ETag>\"etag-" + (first ? 1 : 2) + "\"</ETag><Size>" +
                (first ? 5 : 6) + "</Size></Part><IsTruncated>" + first + "</IsTruncated>" +
                "<NextPartNumberMarker>" + (first ? 1 : 2) + "</NextPartNumberMarker></ListPartsResult>");
        } else if ("POST".equals(method) && query.contains("uploadId=")) {
            completion.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(exchange, 200, completionError.get() ? "<Error><Code>InvalidPart</Code></Error>" :
                "<CompleteMultipartUploadResult><ETag>\"final-etag\"</ETag></CompleteMultipartUploadResult>");
        } else if ("DELETE".equals(method) && query.contains("uploadId=")) {
            reply(exchange, 204, "");
        } else throw new AssertionError("Unexpected multipart operation: " + method + " " + query);
    }

    private static void reply(HttpExchange exchange, int status, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0) exchange.sendResponseHeaders(status, -1);
        else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
