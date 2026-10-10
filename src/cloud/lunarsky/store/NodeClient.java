package cloud.lunarsky.store;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class NodeClient {
    record Node(UUID id, UUID hostId, URI url) {}
    record StoredSegment(UUID id, long modified) {}

    private static final HttpClient IDENTITY_HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();
    private final List<Node> nodes;
    private final String token;
    private final String repairToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ConcurrentHashMap<UUID, Long> unreadableUntil = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> healthyUntil = new ConcurrentHashMap<>();
    private static final long READ_RETRY_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final long HEALTH_FRESH_NANOS = TimeUnit.SECONDS.toNanos(3);

    NodeClient(List<Node> nodes, String token, String repairToken) {
        if (nodes.isEmpty() || nodes.stream().map(Node::id).distinct().count() != nodes.size() ||
            nodes.stream().map(Node::url).distinct().count() != nodes.size())
            throw new IllegalArgumentException("Cluster node IDs and URLs must be unique");
        if (token == null || token.length() < 32) throw new IllegalArgumentException("Invalid cluster token");
        for (Node node : nodes) {
            if (node.id() == null || node.hostId() == null)
                throw new IllegalArgumentException("Invalid storage node identity");
            validateUrl(node.url());
        }
        this.nodes = List.copyOf(nodes);
        this.token = token;
        this.repairToken = repairToken;
    }

    int count() { return nodes.size(); }
    List<Node> nodes() { return nodes; }
    Node node(int index) { return nodes.get(index); }
    boolean repairTokenUnavailable() { return repairToken == null || repairToken.length() < 32; }
    int index(UUID id) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).id().equals(id)) return i;
        }
        return -1;
    }
    UUID faultDomain(int index, boolean testNodeDomains) {
        Node node = nodes.get(index);
        return testNodeDomains ? node.id() : node.hostId();
    }

    static NodeIdentity probe(URI url, String token) throws IOException {
        validateUrl(url);
        if (token == null || token.length() < 32) throw new IllegalArgumentException("Invalid cluster token");
        HttpRequest request = HttpRequest.newBuilder(url.resolve("/identity"))
            .timeout(Duration.ofSeconds(2)).header("X-Cluster-Token", token).GET().build();
        try {
            HttpResponse<InputStream> response = IDENTITY_HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) throw new IOException("Node identity request failed: " + response.statusCode());
                byte[] bytes = body.readNBytes(128);
                if (bytes.length == 128) throw new IOException("Node identity response is too large");
                String[] parts = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII).trim().split(" ", -1);
                if (parts.length != 2) throw new IOException("Invalid node identity response");
                return new NodeIdentity(UUID.fromString(parts[0]), UUID.fromString(parts[1]));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during node identity request", error);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid node identity response", error);
        }
    }

    static NodeIdentity probeIfAvailable(URI url, String token) {
        try { return probe(url, token); }
        catch (IOException offline) { return null; }
    }

    static void validateUrl(URI url) {
        if (url == null || !"http".equals(url.getScheme()) || url.getHost() == null ||
            url.getPort() < 1 || url.getRawUserInfo() != null ||
            (url.getRawPath() != null && !url.getRawPath().isEmpty()) ||
            url.getRawQuery() != null || url.getRawFragment() != null)
            throw new IllegalArgumentException("Invalid private storage node URL");
    }

    boolean availableHostsAtLeast(int required, boolean testNodeDomains) {
        Set<UUID> healthy = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            NodeIdentity actual = probeIfAvailable(node.url(), token);
            if (actual == null || !actual.nodeId().equals(node.id()) || !actual.hostId().equals(node.hostId())) {
                markUnreadable(node);
                continue;
            }
            unreadableUntil.remove(node.id());
            healthyUntil.put(node.id(), System.nanoTime() + HEALTH_FRESH_NANOS);
            healthy.add(faultDomain(i, testNodeDomains));
            if (healthy.size() >= required) return true;
        }
        return false;
    }

    private void markUnreadable(Node node) {
        healthyUntil.remove(node.id());
        unreadableUntil.put(node.id(), System.nanoTime() + READ_RETRY_NANOS);
    }

    private boolean unreadable(Node node) {
        Long until = unreadableUntil.get(node.id());
        return until != null && System.nanoTime() - until < 0;
    }

    private boolean recentlyHealthy(Node node) {
        Long until = healthyUntil.get(node.id());
        return until != null && System.nanoTime() - until < 0;
    }

    void put(int index, UUID id, byte[] data, byte[] sha256) throws IOException {
        put(index, id, data, sha256, false);
    }

    void repair(int index, UUID id, byte[] data, byte[] sha256) throws IOException {
        put(index, id, data, sha256, true);
    }

    private void put(int index, UUID id, byte[] data, byte[] sha256, boolean repair) throws IOException {
        Node node = nodes.get(index);
        HttpRequest.Builder builder = HttpRequest.newBuilder(node.url().resolve("/segments/" + id))
            .timeout(Duration.ofSeconds(30)).header("X-Cluster-Token", token)
            .header("X-Cluster-Expected-Node", node.id().toString())
            .header("X-Cluster-Sha256", HexFormat.of().formatHex(sha256));
        if (repair) {
            if (repairToken == null || repairToken.length() < 32)
                throw new IOException("Repair authority is not available to this process");
            builder.header("X-Cluster-Repair", "true").header("X-Cluster-Repair-Token", repairToken);
        }
        HttpResponse<Void> response = send(builder.PUT(HttpRequest.BodyPublishers.ofByteArray(data)).build(),
            HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) throw new IOException("Node " + node.id() + " rejected segment: " + response.statusCode());
    }

    byte[] get(int index, UUID id, int length, byte[] sha256) throws IOException {
        if (length < 1 || length > ClusterNode.MAX_SEGMENT) throw new IOException("Invalid segment length");
        Node node = nodes.get(index);
        if (unreadable(node)) throw new IOException("Storage node is temporarily unreachable");
        if (!recentlyHealthy(node)) {
            NodeIdentity actual = probeIfAvailable(node.url(), token);
            if (actual == null || !actual.nodeId().equals(node.id()) || !actual.hostId().equals(node.hostId())) {
                markUnreadable(node);
                throw new IOException("Storage node is temporarily unreachable");
            }
            healthyUntil.put(node.id(), System.nanoTime() + HEALTH_FRESH_NANOS);
        }
        HttpRequest request = HttpRequest.newBuilder(node.url().resolve("/segments/" + id))
            .timeout(Duration.ofSeconds(30)).header("X-Cluster-Token", token)
            .header("X-Cluster-Expected-Node", node.id().toString()).GET().build();
        HttpResponse<InputStream> response;
        try { response = send(request, HttpResponse.BodyHandlers.ofInputStream()); }
        catch (IOException error) {
            markUnreadable(node);
            throw error;
        }
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("Node " + node.id() + " has no verified copy of segment " + id);
        }
        byte[] bytes;
        try (InputStream body = response.body()) { bytes = body.readNBytes(length + 1); }
        catch (IOException error) {
            markUnreadable(node);
            throw error;
        }
        if (bytes.length != length || !MessageDigest.isEqual(SigV4.hash(bytes), sha256))
            throw new IOException("Node " + node.id() + " has no verified copy of segment " + id);
        unreadableUntil.remove(node.id());
        healthyUntil.put(node.id(), System.nanoTime() + HEALTH_FRESH_NANOS);
        return bytes;
    }

    List<StoredSegment> inventory(int index, String shard, UUID after) throws IOException {
        if (repairToken == null || repairToken.length() < 32)
            throw new IOException("Repair authority is not available to this process");
        Node node = nodes.get(index);
        String path = "/segments?shard=" + shard + (after == null ? "" : "&after=" + after);
        HttpRequest request = HttpRequest.newBuilder(node.url().resolve(path))
            .timeout(Duration.ofSeconds(30)).header("X-Cluster-Token", token)
            .header("X-Cluster-Expected-Node", node.id().toString())
            .header("X-Cluster-Repair-Token", repairToken).GET().build();
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Node inventory failed: " + response.statusCode());
            byte[] bytes = body.readNBytes(70001);
            if (bytes.length > 70000) throw new IOException("Node inventory response is too large");
            List<StoredSegment> result = new ArrayList<>();
            String last = after == null ? "" : after.toString();
            for (String line : new String(bytes, java.nio.charset.StandardCharsets.US_ASCII).split("\n")) {
                if (line.isEmpty()) continue;
                String[] fields = line.split(" ", -1);
                if (fields.length != 2) throw new IOException("Invalid node inventory response");
                try {
                    UUID id = UUID.fromString(fields[0]);
                    if (!id.toString().equals(fields[0]) || !fields[0].startsWith(shard) ||
                        fields[0].compareTo(last) <= 0)
                        throw new IOException("Invalid node inventory cursor");
                    result.add(new StoredSegment(id, Long.parseLong(fields[1])));
                    last = fields[0];
                } catch (IllegalArgumentException error) {
                    throw new IOException("Invalid node inventory response", error);
                }
            }
            if (result.size() > 1000) throw new IOException("Node inventory page is too large");
            return result;
        }
    }

    boolean deleteOrphan(int index, StoredSegment segment, long minimumAgeMillis) throws IOException {
        if (repairToken == null || repairToken.length() < 32)
            throw new IOException("Repair authority is not available to this process");
        Node node = nodes.get(index);
        HttpRequest request = HttpRequest.newBuilder(node.url().resolve("/segments/" + segment.id()))
            .timeout(Duration.ofSeconds(30)).header("X-Cluster-Token", token)
            .header("X-Cluster-Expected-Node", node.id().toString())
            .header("X-Cluster-Repair-Token", repairToken)
            .header("X-Cluster-Expected-Mtime", Long.toString(segment.modified()))
            .header("X-Cluster-Gc-Min-Age-Millis", Long.toString(minimumAgeMillis))
            .DELETE().build();
        HttpResponse<Void> response = send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 204) return true;
        if (response.statusCode() == 404 || response.statusCode() == 409) return false;
        throw new IOException("Node refused orphan deletion: " + response.statusCode());
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try { return http.send(request, handler); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during node request", error);
        }
    }
}
