package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class ClientLimits {
    private static final int MAX_CLIENTS = 10_000;
    private static final long IDLE_NANOS = 300_000_000_000L;
    private final int requestsPerSecond;
    private final int requestBurst;
    private final long bytesPerSecond;
    private final long byteBurst;
    private final int maxInFlight;
    private final Set<String> trustedProxies;
    private final Map<String, Client> clients = new HashMap<>();
    private long admissions;

    private ClientLimits(int requestsPerSecond, int requestBurst, long bytesPerSecond,
                         long byteBurst, int maxInFlight, Set<String> trustedProxies) {
        this.requestsPerSecond = requestsPerSecond;
        this.requestBurst = requestBurst;
        this.bytesPerSecond = bytesPerSecond;
        this.byteBurst = byteBurst;
        this.maxInFlight = maxInFlight;
        this.trustedProxies = trustedProxies;
    }

    static ClientLimits disabled() {
        return new ClientLimits(0, 0, 0, 0, 0, Set.of());
    }

    static ClientLimits fromEnvironment(Map<String, String> environment) {
        int requests = number(environment, "PUBLIC_REQUESTS_PER_SECOND", 0);
        int requestBurst = number(environment, "PUBLIC_REQUEST_BURST", requests);
        long bytes = longNumber(environment, "PUBLIC_BYTES_PER_SECOND", 0);
        long byteBurst = longNumber(environment, "PUBLIC_BYTE_BURST", bytes);
        int inFlight = number(environment, "PUBLIC_MAX_IN_FLIGHT_PER_IP",
            requests > 0 || bytes > 0 ? 8 : 0);
        if (requests < 0 || requestBurst < 0 || bytes < 0 || byteBurst < 0 || inFlight < 0 ||
            requests > 0 && requestBurst < 1 || requests == 0 && requestBurst != 0 ||
            bytes > 0 && (byteBurst < 1 || byteBurst > 1_073_741_824L) ||
            bytes == 0 && byteBurst != 0 ||
            (requests > 0 || bytes > 0) && inFlight < 1)
            throw new IllegalArgumentException("Invalid public client limits");
        Set<String> proxies = new HashSet<>();
        String configured = environment.getOrDefault("PUBLIC_TRUSTED_PROXY_IPS", "").trim();
        if (!configured.isEmpty()) {
            for (String item : configured.split(",", -1))
                proxies.add(numericAddress(item.trim()).getHostAddress());
        }
        if (requests == 0 && bytes == 0 && inFlight == 0 && !proxies.isEmpty())
            throw new IllegalArgumentException("Trusted proxy IPs require public client limits");
        return new ClientLimits(requests, requestBurst, bytes, byteBurst, inFlight, Set.copyOf(proxies));
    }

    private static int number(Map<String, String> environment, String name, int fallback) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) return fallback;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid " + name, error); }
    }

    private static long longNumber(Map<String, String> environment, String name, long fallback) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) return fallback;
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException("Invalid " + name, error); }
    }

    private static InetAddress numericAddress(String value) {
        try {
            if (value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                String[] parts = value.split("\\.");
                byte[] octets = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(parts[i]);
                    if (octet > 255) throw new IllegalArgumentException("Invalid IP address");
                    octets[i] = (byte) octet;
                }
                return InetAddress.getByAddress(octets);
            }
            if (value.contains(":") && value.matches("[0-9A-Fa-f:.]+"))
                return InetAddress.getByName(value);
        } catch (UnknownHostException error) {
            throw new IllegalArgumentException("Invalid IP address", error);
        }
        throw new IllegalArgumentException("Expected numeric IP address");
    }

    private String address(HttpExchange exchange) {
        InetAddress peer = exchange.getRemoteAddress().getAddress();
        String peerAddress = peer.getHostAddress();
        if (!trustedProxies.contains(peerAddress)) return peerAddress;
        var values = exchange.getRequestHeaders().get("X-Real-IP");
        if (values == null || values.size() != 1)
            throw new StoreException(400, "InvalidRequest", "Trusted proxy must supply one X-Real-IP address");
        try { return numericAddress(values.getFirst()).getHostAddress(); }
        catch (IllegalArgumentException error) {
            throw new StoreException(400, "InvalidRequest", "Trusted proxy supplied an invalid client address");
        }
    }

    Client enter(HttpExchange exchange) {
        if (requestsPerSecond == 0 && bytesPerSecond == 0 && maxInFlight == 0) return null;
        String path = exchange.getRequestURI().getRawPath();
        if (exchange.getRemoteAddress().getAddress().isLoopbackAddress() &&
            exchange.getRequestHeaders().get("X-Real-IP") == null &&
            path.equals("/health")) return null;
        String address = address(exchange);
        Client client;
        synchronized (this) {
            long now = System.nanoTime();
            if (++admissions % 1024 == 0 || clients.size() >= MAX_CLIENTS)
                clients.entrySet().removeIf(entry -> entry.getValue().inFlight == 0 &&
                    now - entry.getValue().lastSeen > IDLE_NANOS);
            client = clients.get(address);
            if (client == null) {
                if (clients.size() >= MAX_CLIENTS)
                    throw new StoreException(503, "SlowDown", "Client limit table is full");
                client = new Client(now, requestBurst, byteBurst);
                clients.put(address, client);
            }
            refill(client, now);
            client.lastSeen = now;
            if (maxInFlight > 0 && client.inFlight >= maxInFlight)
                throw new StoreException(503, "SlowDown", "Too many concurrent requests from this client");
            if (requestsPerSecond > 0 && client.requestTokens < 1)
                throw new StoreException(503, "SlowDown", "Client request rate exceeded");
            if (requestsPerSecond > 0) client.requestTokens--;
            client.inFlight++;
        }
        if (bytesPerSecond > 0) {
            try {
                exchange.setStreams(new LimitedInput(exchange.getRequestBody(), client),
                    new LimitedOutput(exchange.getResponseBody(), client));
            } catch (RuntimeException error) {
                leave(client);
                throw error;
            }
        }
        return client;
    }

    synchronized void leave(Client client) {
        if (client != null) {
            client.inFlight--;
            client.lastSeen = System.nanoTime();
        }
    }

    private void refill(Client client, long now) {
        double seconds = Math.max(0, now - client.lastRefill) / 1_000_000_000.0;
        if (requestsPerSecond > 0)
            client.requestTokens = Math.min(requestBurst, client.requestTokens + seconds * requestsPerSecond);
        if (bytesPerSecond > 0)
            client.byteTokens = Math.min(byteBurst, client.byteTokens + seconds * bytesPerSecond);
        client.lastRefill = now;
    }

    private void pace(Client client, int count) throws IOException {
        while (true) {
            long wait;
            synchronized (this) {
                refill(client, System.nanoTime());
                if (client.byteTokens >= count) {
                    client.byteTokens -= count;
                    return;
                }
                wait = Math.max(1_000_000L,
                    (long) Math.ceil((count - client.byteTokens) * 1_000_000_000.0 / bytesPerSecond));
            }
            try { Thread.sleep(Math.min(wait / 1_000_000L + 1, 1000)); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("Transfer interrupted while waiting for client bandwidth", error);
            }
        }
    }

    private int chunk() { return (int) Math.min(16_384, byteBurst); }

    static final class Client {
        private long lastSeen;
        private long lastRefill;
        private double requestTokens;
        private double byteTokens;
        private int inFlight;

        private Client(long now, int requestBurst, long byteBurst) {
            lastSeen = now;
            lastRefill = now;
            requestTokens = requestBurst;
            byteTokens = byteBurst;
        }
    }

    private final class LimitedInput extends FilterInputStream {
        private final Client client;

        private LimitedInput(java.io.InputStream input, Client client) {
            super(input);
            this.client = client;
        }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value >= 0) pace(client, 1);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, Math.min(length, chunk()));
            if (count > 0) pace(client, count);
            return count;
        }
    }

    private final class LimitedOutput extends FilterOutputStream {
        private final Client client;

        private LimitedOutput(java.io.OutputStream output, Client client) {
            super(output);
            this.client = client;
        }

        @Override public void write(int value) throws IOException {
            pace(client, 1);
            out.write(value);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
            int left = length;
            while (left > 0) {
                int count = Math.min(left, chunk());
                pace(client, count);
                out.write(bytes, offset, count);
                offset += count;
                left -= count;
            }
        }
    }
}
