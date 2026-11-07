package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class NodeHttpTest {
    private static final String TOKEN = "local-node-http-test-token-0123456789";

    public static void main(String[] args) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            stalledBody(client, false);
            stalledBody(client, true);
            oversizedBody(client);
            interruptedBody(client);
            stalledProbe(client);
            nodeResponseLimits(client);
        }
        System.out.println("Node HTTP tests passed: total deadlines, cancellation, bounded responses, interruption");
    }

    private static void stalledBody(HttpClient client, boolean discard) throws Exception {
        try (StalledResponse server = new StalledResponse(1)) {
            HttpRequest request = HttpRequest.newBuilder(server.uri()).timeout(Duration.ofMillis(500)).GET().build();
            long started = System.nanoTime();
            expectFailure(() -> {
                if (discard) NodeHttp.discard(client, request);
                else NodeHttp.bytes(client, request, 32);
            }, "timed out");
            require(server.sent.await(1, TimeUnit.SECONDS), "Request did not reach the stalled response body");
            require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3), "Body timeout exceeded its deadline");
            server.requireCancelled();
        }
    }

    private static void oversizedBody(HttpClient client) throws Exception {
        try (StalledResponse server = new StalledResponse(33)) {
            HttpRequest request = HttpRequest.newBuilder(server.uri()).timeout(Duration.ofSeconds(5)).GET().build();
            long started = System.nanoTime();
            expectFailure(() -> NodeHttp.bytes(client, request, 32), "too large");
            require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(3), "Oversized body waited for EOF");
            server.requireCancelled();
        }
    }

    private static void interruptedBody(HttpClient client) throws Exception {
        try (StalledResponse server = new StalledResponse(1)) {
            HttpRequest request = HttpRequest.newBuilder(server.uri()).timeout(Duration.ofSeconds(5)).GET().build();
            CompletableFuture<Boolean> interrupted = new CompletableFuture<>();
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    NodeHttp.bytes(client, request, 32);
                    interrupted.complete(false);
                } catch (IOException expected) {
                    interrupted.complete(Thread.currentThread().isInterrupted());
                }
            });
            try {
                require(server.sent.await(2, TimeUnit.SECONDS), "Interrupted request never received headers");
                worker.interrupt();
                require(interrupted.get(2, TimeUnit.SECONDS), "Node request did not preserve interruption");
                server.requireCancelled();
            } finally {
                worker.interrupt();
                worker.join(2000);
            }
        }
    }

    private static void stalledProbe(HttpClient client) throws Exception {
        try (StalledResponse server = new StalledResponse(1)) {
            long started = System.nanoTime();
            expectFailure(() -> NodeClient.probe(server.uri(), TOKEN, client), "timed out");
            require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(4), "Identity body ignored probe timeout");
            server.requireCancelled();
        }
    }

    private static void nodeResponseLimits(HttpClient http) throws Exception {
        UUID nodeId = UUID.randomUUID();
        UUID hostId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/identity", exchange -> {
            byte[] identity = (nodeId + " " + hostId).getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(200, identity.length);
            exchange.getResponseBody().write(identity);
            exchange.close();
        });
        server.createContext("/segments", exchange -> {
            byte[] body = new byte[exchange.getRequestURI().getRawQuery() == null ? 6 : 70001];
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            NodeClient client = new NodeClient(List.of(new NodeClient.Node(nodeId, hostId, uri)), TOKEN, TOKEN, http);
            expectFailure(() -> client.get(0, UUID.randomUUID(), 4, SigV4.hash(new byte[4])), "too large");
            expectFailure(() -> client.inventory(0, "00", null), "too large");
        } finally {
            server.stop(0);
        }
    }

    @FunctionalInterface
    private interface Request { void run() throws IOException; }

    private static void expectFailure(Request request, String message) throws IOException {
        try {
            request.run();
            throw new AssertionError("Invalid node response was accepted");
        } catch (IOException expected) {
            require(expected.getMessage().contains(message), "Unexpected failure: " + expected);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class StalledResponse implements AutoCloseable {
        private final ServerSocket server = new ServerSocket();
        private final CountDownLatch sent = new CountDownLatch(1);
        private final CompletableFuture<Boolean> cancelled = new CompletableFuture<>();
        private final Thread worker;
        private volatile Socket peer;

        StalledResponse(int bodyBytes) throws IOException {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            worker = Thread.ofVirtual().start(() -> serve(bodyBytes));
        }

        URI uri() { return URI.create("http://127.0.0.1:" + server.getLocalPort()); }

        private void serve(int bodyBytes) {
            try (Socket socket = server.accept()) {
                peer = socket;
                socket.setSoTimeout(6000);
                var input = socket.getInputStream();
                int tail = 0;
                int count = 0;
                while (tail != 0x0d0a0d0a && count++ < 16384) {
                    int next = input.read();
                    if (next < 0) throw new IOException("Request ended before headers");
                    tail = (tail << 8) | next;
                }
                var output = socket.getOutputStream();
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                output.write(new byte[bodyBytes]);
                output.flush();
                sent.countDown();
                try { cancelled.complete(input.read() == -1); }
                catch (SocketException closed) { cancelled.complete(true); }
            } catch (Exception error) {
                cancelled.completeExceptionally(error);
            }
        }

        void requireCancelled() throws Exception {
            require(cancelled.get(2, TimeUnit.SECONDS), "Cancelled body left its connection open");
        }

        @Override
        public void close() throws Exception {
            server.close();
            if (peer != null) peer.close();
            worker.join(2000);
        }
    }
}
