package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

final class ObjectStoreServer {
    private ObjectStoreServer() { }

    static void start() throws Exception {
        Map<String, String> env = System.getenv();
        String access = required(env, "S3_ACCESS_KEY"), secret = required(env, "S3_SECRET_KEY");
        String bucket = env.getOrDefault("S3_BUCKET", "lunaris-files"), region = env.getOrDefault("S3_REGION", "us-east-1");
        if (!access.matches("[A-Za-z0-9]{16,128}") || secret.length() < 32 ||
            !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))
            throw new IllegalArgumentException("Invalid storage credentials/bucket configuration");
        ObjectStorage.Limits limits = StorageLimits.fromEnvironment(env);
        long maxObject = limits.maxObjectBytes(), maxTotal = limits.maxTotalBytes();
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
            multipart = (ClusterStore) store;
        } else if (mode.equals("disk")) {
            DiskStore disk = new DiskStore(Path.of(env.getOrDefault("DATA_DIR", "/data")), maxObject, maxTotal);
            store = disk;
            multipart = new MultipartStore(disk);
        } else throw new IllegalArgumentException("Invalid STORE_MODE");
        Map<String, String> identities = identities(env, access, secret);
        var sessions = SessionCredentials.fromEnvironment(env, identities.keySet());
        var allIdentities = new java.util.HashSet<>(identities.keySet());
        allIdentities.addAll(sessions.keySet());
        var app = new Main(store, multipart, new SigV4(identities, sessions,
            access, region, Clock.systemUTC()), bucket, ClientLimits.fromEnvironment(env),
            positiveInt(env, "MAX_IN_FLIGHT_REQUESTS", 16, 1024),
            env.getOrDefault("S3_VIRTUAL_HOST_SUFFIX", ""), BrowserCors.fromEnvironment(env),
            KeyScopes.fromEnvironment(env, access, allIdentities),
            new RequestMetrics(env.get("METRICS_BEARER_TOKEN")),
            IdentityPolicies.fromEnvironment(env, access, allIdentities),
            publicEndpoint(env));
        int port = Integer.parseInt(env.getOrDefault("PORT", "9000"));
        var server = HttpServer.create(mode.equals("cluster")
            ? new InetSocketAddress(env.getOrDefault("BIND_ADDRESS", "127.0.0.1"), port)
            : new InetSocketAddress(port), positiveInt(env, "HTTP_BACKLOG", 64, 4096));
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", app::handle);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(5);
            executor.close();
            try { store.close(); }
            catch (IOException error) { System.err.println("Could not release ObjectStore data lock: " + error.getMessage()); }
        }));
        server.start();
        if (store instanceof DiskStore disk) printStartup(disk, multipart, bucket, region, port);
        else System.out.println("ObjectStore local cluster prototype v" + Version.VALUE + " listening on :" + port);
    }

    static Map<String, String> identities(Map<String, String> env, String access, String secret)
        throws IOException {
        Map<String, String> values = new HashMap<>();
        values.put(access, secret);
        String file = env.get("S3_CREDENTIALS_FILE");
        if (file == null || file.isBlank()) return values;
        List<String> lines = java.nio.file.Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
        if (lines.size() > 63) throw new IllegalArgumentException("Too many additional identities");
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int separator = line.indexOf(':');
            if (separator < 0) throw new IllegalArgumentException("Invalid additional credential entry");
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            if (!key.matches("[A-Za-z0-9]{16,128}") || value.length() < 32 ||
                values.putIfAbsent(key, value) != null)
                throw new IllegalArgumentException("Invalid or duplicate additional credential");
        }
        return values;
    }

    static URI publicEndpoint(Map<String, String> env) {
        String configured = env.get("S3_PUBLIC_ENDPOINT");
        if (configured == null || configured.isBlank()) return null;
        URI uri;
        try { uri = URI.create(configured); }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid S3_PUBLIC_ENDPOINT", error);
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()) ||
            uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null ||
            uri.getRawFragment() != null ||
            !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/")))
            throw new IllegalArgumentException("S3_PUBLIC_ENDPOINT must be an HTTP(S) origin");
        return URI.create(configured.endsWith("/") ? configured.substring(0, configured.length() - 1) : configured);
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
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }
    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static int positiveInt(Map<String, String> env, String key, int fallback, int maximum) {
        String configured = env.get(key);
        if (configured == null || configured.isBlank()) return fallback;
        try {
            int value = Integer.parseInt(configured);
            if (value < 1 || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be between 1 and " + maximum, error);
        }
    }
}
