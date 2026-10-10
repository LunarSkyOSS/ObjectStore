package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

/** Serves verified object segments to authenticated cluster peers. */
public final class ClusterNode implements AutoCloseable {
    static final int MAX_SEGMENT = 8 * 1024 * 1024;
    private final Path root, segments, pending;
    private final byte[] token;
    private final byte[] repairToken;
    private final NodeIdentity identity;
    private final FileChannel lockChannel;
    private final FileLock lock;

    ClusterNode(Path root, String token, String repairToken, UUID hostId) throws IOException {
        if (token == null || token.length() < 32) throw new IllegalArgumentException("Cluster token must have at least 32 characters");
        if (repairToken == null || repairToken.length() < 32 || repairToken.equals(token))
            throw new IllegalArgumentException("A separate repair token of at least 32 characters is required");
        if (hostId == null) throw new IllegalArgumentException("Storage host ID is required");
        this.root = root;
        this.token = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.repairToken = repairToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        segments = root.resolve("segments");
        pending = root.resolve("pending");
        Files.createDirectories(root);
        lockChannel = FileChannel.open(root.resolve(".process.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired;
        try { acquired = lockChannel.tryLock(); }
        catch (OverlappingFileLockException error) {
            lockChannel.close();
            throw new IOException("Node data directory is already in use", error);
        }
        if (acquired == null) {
            lockChannel.close();
            throw new IOException("Node data directory is already in use");
        }
        lock = acquired;
        try {
            identity = NodeIdentity.open(root, hostId);
            Files.createDirectories(segments);
            Files.createDirectories(pending);
            DiskStore.syncDirectory(root);
            try (var files = Files.list(pending)) {
                for (Path file : files.toList()) {
                    if (!Files.isRegularFile(file)) throw new IOException("Invalid pending entry: " + file);
                    Files.delete(file);
                }
            }
            DiskStore.syncDirectory(pending);
        } catch (IOException error) {
            close();
            throw error;
        }
    }

    void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/health") && exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200, "ok");
                return;
            }
            if (!authorized(exchange)) return;
            if (path.equals("/identity") && exchange.getRequestMethod().equals("GET")) {
                respond(exchange, 200, identity.nodeId() + " " + identity.hostId());
                return;
            }
            if (!expectedNodeAndRepairAuthorized(exchange)) return;
            String id = segmentId(exchange, path);
            if (id == null) return;
            switch (exchange.getRequestMethod()) {
                case "PUT" -> put(exchange, segmentPath(id, true));
                case "GET" -> get(exchange, segmentPath(id, false));
                default -> respond(exchange, 405, "Method not allowed");
            }
        } catch (IllegalArgumentException error) {
            respond(exchange, 400, "Invalid request");
        } catch (IOException error) {
            if (exchange.getResponseCode() == -1) respond(exchange, 500, "Storage failure");
            throw error;
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) throws IOException {
        byte[] supplied = exchange.getRequestHeaders().getFirst("X-Cluster-Token") == null
            ? new byte[0] : exchange.getRequestHeaders().getFirst("X-Cluster-Token")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(token, supplied)) {
            respond(exchange, 403, "Forbidden");
            return false;
        }
        return true;
    }

    private boolean expectedNodeAndRepairAuthorized(HttpExchange exchange) throws IOException {
        if (!identity.nodeId().toString().equals(exchange.getRequestHeaders().getFirst("X-Cluster-Expected-Node"))) {
            respond(exchange, 409, "Wrong storage node");
            return false;
        }
        if (exchange.getRequestMethod().equals("PUT") &&
            "true".equals(exchange.getRequestHeaders().getFirst("X-Cluster-Repair"))) {
            String suppliedRepair = exchange.getRequestHeaders().getFirst("X-Cluster-Repair-Token");
            byte[] suppliedBytes = suppliedRepair == null ? new byte[0]
                : suppliedRepair.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(repairToken, suppliedBytes)) {
                respond(exchange, 403, "Repair authority required");
                return false;
            }
        }
        return true;
    }

    private static String segmentId(HttpExchange exchange, String path) throws IOException {
        if (!path.matches("/segments/[0-9a-f-]{36}")) {
            respond(exchange, 404, "Not found");
            return null;
        }
        String id = path.substring("/segments/".length());
        if (!UUID.fromString(id).toString().equals(id)) {
            respond(exchange, 400, "Invalid segment ID");
            return null;
        }
        return id;
    }

    private synchronized Path segmentPath(String id, boolean createShard) throws IOException {
        Path shard = segments.resolve(id.substring(0, 2));
        if (createShard && !Files.isDirectory(shard)) {
            Files.createDirectories(shard);
            DiskStore.syncDirectory(segments);
        }
        return shard.resolve(id);
    }

    private void put(HttpExchange exchange, Path target) throws IOException {
        String hash = exchange.getRequestHeaders().getFirst("X-Cluster-Sha256");
        String lengthText = exchange.getRequestHeaders().getFirst("Content-Length");
        if (hash == null || !hash.matches("[0-9a-f]{64}") || lengthText == null) {
            respond(exchange, 400, "Missing checksum or length");
            return;
        }
        long length = Long.parseLong(lengthText);
        if (length < 1 || length > MAX_SEGMENT) {
            respond(exchange, 413, "Segment too large");
            return;
        }
        Path temp = Files.createTempFile(pending, "segment-", ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try (InputStream input = exchange.getRequestBody(); OutputStream output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[65536];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    count += read;
                    if (count > length) {
                        respond(exchange, 400, "Body longer than declared length");
                        return;
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (count != length || !hash.equals(HexFormat.of().formatHex(digest.digest()))) {
                respond(exchange, 400, "Segment checksum mismatch");
                return;
            }
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            synchronized (this) {
                if (Files.exists(target)) {
                    byte[] existing = Files.readAllBytes(target);
                    if (!Arrays.equals(existing, Files.readAllBytes(temp))) {
                        if (!"true".equals(exchange.getRequestHeaders().getFirst("X-Cluster-Repair")) ||
                            hash.equals(HexFormat.of().formatHex(SigV4.hash(existing)))) {
                            respond(exchange, 409, "Segment ID conflict");
                            return;
                        }
                        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        DiskStore.syncDirectory(target.getParent());
                    }
                } else {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                    DiskStore.syncDirectory(target.getParent());
                }
            }
            respond(exchange, 200, "ok");
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void get(HttpExchange exchange, Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            respond(exchange, 404, "Segment not found");
            return;
        }
        long length = Files.size(target);
        if (length > MAX_SEGMENT) throw new IOException("Segment exceeds maximum length");
        exchange.sendResponseHeaders(200, length);
        try (InputStream input = Files.newInputStream(target)) {
            input.transferTo(exchange.getResponseBody());
        }
    }

    private static void respond(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override public void close() throws IOException {
        lock.release();
        lockChannel.close();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        String token = env.get("CLUSTER_TOKEN");
        var node = new ClusterNode(Path.of(env.getOrDefault("DATA_DIR", "/data")), token,
            env.get("CLUSTER_REPAIR_TOKEN"),
            UUID.fromString(env.get("CLUSTER_HOST_ID")));
        int port = Integer.parseInt(env.getOrDefault("NODE_PORT", "9100"));
        var server = HttpServer.create(new InetSocketAddress(env.getOrDefault("NODE_BIND", "127.0.0.1"), port), 64);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", node::handle);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(5);
            executor.close();
            try { node.close(); } catch (IOException error) { System.err.println("Node close failed: " + error); }
        }));
        server.start();
        System.out.println("ObjectStore cluster node listening on :" + port);
    }
}
