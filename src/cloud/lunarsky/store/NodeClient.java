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
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class NodeClient {
    record Node(UUID id, UUID hostId, URI url) {}

    private static final HttpClient IDENTITY_HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2)).build();
    private final List<Node> nodes;
    private final String token;
    private final String repairToken;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

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
    int index(UUID id) {
        for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).id().equals(id)) return i;
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
            try {
                NodeIdentity actual = probe(node.url(), token);
                if (actual.nodeId().equals(node.id()) && actual.hostId().equals(node.hostId()))
                    healthy.add(faultDomain(i, testNodeDomains));
                if (healthy.size() >= required) return true;
            } catch (IOException error) { }
        }
        return false;
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
        HttpRequest request = HttpRequest.newBuilder(node.url().resolve("/segments/" + id))
            .timeout(Duration.ofSeconds(30)).header("X-Cluster-Token", token)
            .header("X-Cluster-Expected-Node", node.id().toString()).GET().build();
        HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200)
                throw new IOException("Node " + node.id() + " has no verified copy of segment " + id);
            byte[] bytes = body.readNBytes(length + 1);
            if (bytes.length != length || !MessageDigest.isEqual(SigV4.hash(bytes), sha256))
                throw new IOException("Node " + node.id() + " has no verified copy of segment " + id);
            return bytes;
        }
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try { return http.send(request, handler); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during node request", error);
        }
    }
}
