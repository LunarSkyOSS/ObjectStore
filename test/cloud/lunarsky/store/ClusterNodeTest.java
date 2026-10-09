package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public final class ClusterNodeTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("objectstore-node-");
        String token = "local-cluster-test-token-0123456789";
        String repairToken = "local-repair-test-token-0123456789";
        UUID id = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        UUID nodeId;
        byte[] value = "a durable segment".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (ClusterNode node = new ClusterNode(root, token, repairToken, hostId)) {
            try {
                new ClusterNode(root, token, repairToken, hostId);
                throw new AssertionError("Second writer opened a locked node directory");
            } catch (IOException expected) { }
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", node::handle);
            server.start();
            try {
                URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
                NodeIdentity identity = NodeClient.probe(uri, token);
                nodeId = identity.nodeId();
                require(identity.hostId().equals(hostId), "Node reported the wrong storage host");
                NodeClient client = new NodeClient(List.of(new NodeClient.Node(identity.nodeId(), hostId, uri)),
                    token, repairToken);
                client.put(0, id, value, SigV4.hash(value));
                client.put(0, id, value, SigV4.hash(value));
                require(java.util.Arrays.equals(value, client.get(0, id, value.length, SigV4.hash(value))), "Roundtrip failed");
                var request = HttpRequest.newBuilder(uri.resolve("/segments/" + id)).timeout(Duration.ofSeconds(3))
                    .header("X-Cluster-Token", token).header("X-Cluster-Expected-Node", nodeId.toString())
                    .header("X-Cluster-Sha256", SigV4.hex(SigV4.hash(value)))
                    .PUT(HttpRequest.BodyPublishers.ofString("wrong")).build();
                var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
                require(response.statusCode() == 400, "Wrong checksum accepted");
                var unauthenticated = HttpRequest.newBuilder(uri.resolve("/segments/" + id)).GET().build();
                require(HttpClient.newHttpClient().send(unauthenticated, HttpResponse.BodyHandlers.ofString())
                    .statusCode() == 403, "Unauthenticated read accepted");
                var wrongNode = HttpRequest.newBuilder(uri.resolve("/segments/" + id)).timeout(Duration.ofSeconds(3))
                    .header("X-Cluster-Token", token).header("X-Cluster-Expected-Node", UUID.randomUUID().toString())
                    .GET().build();
                require(HttpClient.newHttpClient().send(wrongNode, HttpResponse.BodyHandlers.ofString())
                    .statusCode() == 409, "Wrong-node request was accepted");
                Path segment = root.resolve("segments").resolve(id.toString().substring(0, 2)).resolve(id.toString());
                Files.writeString(segment, "corrupted");
                try {
                    client.get(0, id, value.length, SigV4.hash(value));
                    throw new AssertionError("Corrupted replica passed verification");
                } catch (IOException expected) { }
                NodeClient gateway = new NodeClient(List.of(new NodeClient.Node(identity.nodeId(), hostId, uri)),
                    token, null);
                try {
                    gateway.repair(0, id, value, SigV4.hash(value));
                    throw new AssertionError("Gateway was allowed to repair a replica");
                } catch (IOException expected) { }
                var forgedRepair = HttpRequest.newBuilder(uri.resolve("/segments/" + id))
                    .header("X-Cluster-Token", token).header("X-Cluster-Expected-Node", nodeId.toString())
                    .header("X-Cluster-Repair", "true").header("X-Cluster-Sha256", SigV4.hex(SigV4.hash(value)))
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(value)).build();
                require(HttpClient.newHttpClient().send(forgedRepair, HttpResponse.BodyHandlers.discarding())
                    .statusCode() == 403, "Shared node token was allowed to repair a replica");
                client.repair(0, id, value, SigV4.hash(value));
                require(java.util.Arrays.equals(value, client.get(0, id, value.length, SigV4.hash(value))),
                    "Repair did not restore the original bytes");
            } finally { server.stop(0); }
        }
        Path pending = root.resolve("pending").resolve("unfinished.part");
        Files.writeString(pending, "unfinished");
        try (ClusterNode restarted = new ClusterNode(root, token, repairToken, hostId)) {
            require(!Files.exists(pending), "Incomplete segment survived restart");
            Path segment = root.resolve("segments").resolve(id.toString().substring(0, 2)).resolve(id.toString());
            require(java.util.Arrays.equals(value, Files.readAllBytes(segment)), "Committed segment did not survive restart");
        }
        require(NodeIdentity.open(root, hostId).nodeId().equals(nodeId),
            "Node identity changed after restart");
        try {
            new ClusterNode(root, token, repairToken, UUID.randomUUID());
            throw new AssertionError("Node volume accepted a changed host identity");
        } catch (IOException expected) { }
        HttpServer oversized = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        oversized.createContext("/identity", exchange -> {
            byte[] bytes = new byte[1024];
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        oversized.createContext("/segments/", exchange -> {
            byte[] bytes = new byte[1024];
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        oversized.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + oversized.getAddress().getPort());
            try {
                NodeClient.probe(uri, token);
                throw new AssertionError("Oversized identity response was accepted");
            } catch (IOException expected) { }
            NodeClient client = new NodeClient(List.of(new NodeClient.Node(nodeId, hostId, uri)), token, null);
            try {
                client.get(0, id, value.length, SigV4.hash(value));
                throw new AssertionError("Oversized segment response was accepted");
            } catch (IOException expected) { }
        } finally { oversized.stop(0); }
        System.out.println("Cluster node tests passed: lock, authenticated roundtrip, checksums, restart cleanup");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
