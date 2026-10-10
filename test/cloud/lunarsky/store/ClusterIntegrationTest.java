package cloud.lunarsky.store;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

public final class ClusterIntegrationTest {
    private static final String KEY = "cluster-test/survivor";
    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        String[] urls = env.get("CLUSTER_NODES").split(",");
        try (ClusterStore store = new ClusterStore(env.get("POSTGRES_JDBC_URL"), env.get("POSTGRES_USER"),
                env.get("POSTGRES_PASSWORD"), env.get("S3_BUCKET"),
                Arrays.stream(urls).map(URI::create).toList(), env.get("CLUSTER_TOKEN"), null,
                134217728, 2147483648L,
                !"same-host".equals(args[0]) && "true".equals(env.get("CLUSTER_TEST_NODE_DOMAINS")))) {
            String bucket = env.get("S3_BUCKET");
            byte[] stable = "acknowledged object survives node loss".getBytes(StandardCharsets.UTF_8);
            switch (args[0]) {
                case "basic" -> {
                    byte[] large = new byte[ClusterNode.MAX_SEGMENT + 37];
                    for (int i = 0; i < large.length; i++) {
                        large[i] = (byte) (i * 31);
                    }
                    String largeKey = "cluster-test/large";
                    put(store, bucket, largeKey, large, false);
                    try (var opened = store.open(bucket, largeKey)) {
                        require(opened.metadata().length() == large.length, "Wrong object length");
                        require(Arrays.equals(large, opened.stream().readAllBytes()), "Multi-segment read mismatch");
                    }
                    require(store.list(bucket, "cluster-test/", "", 10, null).keyCount() >= 1, "Listing missed object");
                    try {
                        put(store, bucket, largeKey, large, true);
                        throw new AssertionError("Create-only overwrite succeeded");
                    } catch (StoreException error) { require(error.status == 412, "Wrong create-only status"); }
                    put(store, bucket, largeKey, "replacement".getBytes(StandardCharsets.UTF_8), false);
                    try (var opened = store.open(bucket, largeKey)) {
                        require("replacement".equals(new String(opened.stream().readAllBytes(), StandardCharsets.UTF_8)),
                            "Overwrite was not visible");
                    }
                    store.delete(bucket, largeKey);
                    try {
                        store.open(bucket, largeKey);
                        throw new AssertionError("Deleted object remained visible");
                    }
                    catch (StoreException error) { require(error.status == 404, "Wrong missing-object status"); }
                    put(store, bucket, KEY, stable, false);
                    require(store.ready(), "Healthy cluster is not ready");
                    System.out.println("Cluster basic test passed");
                }
                case "degraded" -> {
                    require(store.ready(), "Two available nodes should be ready");
                    try (var opened = store.open(bucket, KEY)) {
                        require(Arrays.equals(stable, opened.stream().readAllBytes()), "Acknowledged object was lost");
                    }
                    byte[] value = "written with one node down".getBytes(StandardCharsets.UTF_8);
                    put(store, bucket, "cluster-test/degraded", value, false);
                    try (var opened = store.open(bucket, "cluster-test/degraded")) {
                        require(Arrays.equals(value, opened.stream().readAllBytes()), "Degraded write was not readable");
                    }
                    System.out.println("Cluster degraded test passed");
                }
                case "multipart-stage" -> {
                    String key = "cluster-test/multipart";
                    String id = store.create(bucket, key, "text/plain");
                    byte[] first = "hello ".getBytes(StandardCharsets.UTF_8);
                    byte[] second = "world".getBytes(StandardCharsets.UTF_8);
                    putPart(store, id, bucket, key, 1, "old".getBytes(StandardCharsets.UTF_8));
                    putPart(store, id, bucket, key, 1, first);
                    putPart(store, id, bucket, key, 2, second);
                    require(store.activeUploads() == 1, "Upload was not retained");
                    require(store.stagedBytes() == first.length + second.length, "Replaced part was counted twice");
                    require(store.listUploads(bucket, key).size() == 1, "Upload listing missed the staged upload");
                    var firstPage = store.listParts(id, bucket, key, 0, 1);
                    require(firstPage.truncated() && firstPage.parts().size() == 1 && firstPage.nextMarker() == 1,
                        "Part listing did not paginate");
                    require(store.listParts(id, bucket, key, 1, 1).parts().getFirst().number() == 2,
                        "Part marker skipped the second part");
                    try {
                        store.open(bucket, key);
                        throw new AssertionError("Incomplete upload became visible");
                    } catch (StoreException error) { require(error.status == 404, "Wrong incomplete-upload status"); }
                    System.out.println("Cluster multipart parts staged and listed");
                }
                case "multipart-complete" -> {
                    String key = "cluster-test/multipart";
                    var uploads = store.listUploads(bucket, key);
                    require(uploads.size() == 1, "Upload did not survive gateway restart");
                    String id = uploads.getFirst().id();
                    var listed = store.listParts(id, bucket, key, 0, 1000).parts();
                    require(listed.size() == 2, "Staged parts were lost");
                    try {
                        store.complete(id, bucket, key, List.of(new MultipartStorage.Part(1, "0".repeat(32))));
                        throw new AssertionError("Wrong part ETag was accepted");
                    } catch (StoreException error) { require(error.status == 400, "Wrong ETag rejection status"); }
                    var completed = store.complete(id, bucket, key, List.of(
                        new MultipartStorage.Part(1, listed.get(0).etag()),
                        new MultipartStorage.Part(2, listed.get(1).etag())));
                    byte[] expected = "hello world".getBytes(StandardCharsets.UTF_8);
                    require(completed.length() == expected.length && completed.contentType().equals("text/plain"),
                        "Completed object metadata is wrong");
                    MessageDigest digest = MessageDigest.getInstance("MD5");
                    digest.update(HexFormat.of().parseHex(listed.get(0).etag()));
                    digest.update(HexFormat.of().parseHex(listed.get(1).etag()));
                    require(completed.etag().equals(HexFormat.of().formatHex(digest.digest()) + "-2"),
                        "Multipart ETag is wrong");
                    try (var opened = store.open(bucket, key)) {
                        require(Arrays.equals(opened.stream().readAllBytes(), expected), "Completed multipart body is wrong");
                    }
                    require(store.activeUploads() == 0 && store.stagedBytes() == 0, "Completed parts still count as staged");
                    String aborted = store.create(bucket, "cluster-test/aborted", "text/plain");
                    putPart(store, aborted, bucket, "cluster-test/aborted", 1, expected);
                    store.abort(aborted, bucket, "cluster-test/aborted");
                    require(store.activeUploads() == 0 && store.stagedBytes() == 0, "Aborted parts still count as staged");
                    System.out.println("Cluster multipart completion survived restart and node loss");
                }
                case "quorum-lost" -> {
                    require(!store.ready(), "One available node must not be ready");
                    try {
                        put(store, bucket, "cluster-test/rejected", new byte[]{1}, false);
                        throw new AssertionError("Write succeeded with only one node");
                    } catch (StoreException error) { require(error.status == 503, "Wrong unavailable status"); }
                    try {
                        store.open(bucket, "cluster-test/rejected");
                        throw new AssertionError("Failed write became visible");
                    }
                    catch (StoreException error) { require(error.status == 404, "Partial object became visible"); }
                    System.out.println("Cluster quorum-loss test passed");
                }
                case "recovered" -> {
                    require(store.ready(), "Restarted cluster is not ready");
                    try (var opened = store.open(bucket, KEY)) {
                        require(Arrays.equals(stable, opened.stream().readAllBytes()), "Object lost across restart");
                    }
                    System.out.println("Cluster recovery test passed");
                }
                case "concurrent" -> {
                    String key = "cluster-test/concurrent";
                    byte[] first = "concurrent-first".getBytes(StandardCharsets.UTF_8);
                    byte[] second = "concurrent-second".getBytes(StandardCharsets.UTF_8);
                    var start = new java.util.concurrent.CountDownLatch(1);
                    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
                    try {
                        var a = executor.submit(() -> {
                            start.await();
                            put(store, bucket, key, first, false);
                            return null;
                        });
                        var b = executor.submit(() -> {
                            start.await();
                            put(store, bucket, key, second, false);
                            return null;
                        });
                        start.countDown();
                        a.get();
                        b.get();
                        try (var opened = store.open(bucket, key)) {
                            byte[] actual = opened.stream().readAllBytes();
                            require(Arrays.equals(actual, first) || Arrays.equals(actual, second),
                                "Concurrent PUT produced a partial object");
                        }
                        require(store.list(bucket, key, "", 10, null).keyCount() == 1,
                            "Concurrent PUT produced duplicate key entries");
                    } finally { executor.shutdownNow(); }
                    System.out.println("Cluster concurrent overwrite test passed");
                }
                case "same-host" -> {
                    require(!store.ready(), "Containers on one physical host must not form a storage quorum");
                    try {
                        put(store, bucket, "cluster-test/same-host-rejected", new byte[]{1}, false);
                        throw new AssertionError("Write succeeded without two physical storage hosts");
                    } catch (StoreException error) { require(error.status == 503, "Wrong same-host rejection status"); }
                    System.out.println("Same-host replicas correctly fail the physical-host quorum");
                }
                case "joined" -> {
                    require(urls.length == 4, "Expansion test requires four registered nodes");
                    var newNode = NodeClient.probe(URI.create(urls[3]), env.get("CLUSTER_TOKEN"));
                    for (int i = 0; i < 32; i++) {
                        String key = "cluster-test/expanded-" + i;
                        byte[] value = ("expanded object " + i).getBytes(StandardCharsets.UTF_8);
                        put(store, bucket, key, value, false);
                        try (var opened = store.open(bucket, key)) {
                            require(Arrays.equals(value, opened.stream().readAllBytes()), "Expanded object was not readable");
                        }
                    }
                    try (var connection = java.sql.DriverManager.getConnection(env.get("POSTGRES_JDBC_URL"),
                            env.get("POSTGRES_USER"), env.get("POSTGRES_PASSWORD"));
                         var query = connection.prepareStatement(
                             "SELECT count(*) FROM cluster_segments WHERE ? = ANY(replica_ids)")) {
                        query.setObject(1, newNode.nodeId());
                        try (var result = query.executeQuery()) {
                            result.next();
                            require(result.getLong(1) > 0, "Joined node received no new segments");
                        }
                    }
                    System.out.println("Joined node accepted new placements while previous objects stayed readable");
                }
                case "verify-expanded" -> {
                    for (int i = 0; i < 32; i++) {
                        try (var opened = store.open(bucket, "cluster-test/expanded-" + i)) {
                            require(("expanded object " + i).equals(new String(opened.stream().readAllBytes(),
                                StandardCharsets.UTF_8)), "Expanded object was lost during maintenance");
                        }
                    }
                    System.out.println("Expanded objects survived repair and cleanup");
                }
                case "balanced" -> {
                    try (var connection = java.sql.DriverManager.getConnection(env.get("POSTGRES_JDBC_URL"),
                            env.get("POSTGRES_USER"), env.get("POSTGRES_PASSWORD"))) {
                        NodeClient nodes = NodeRegistry.load(connection,
                            Arrays.stream(urls).map(URI::create).toList(), env.get("CLUSTER_TOKEN"), null);
                        int checked = 0;
                        try (var query = connection.createStatement();
                             var result = query.executeQuery("SELECT s.segment_id, s.replica_ids FROM cluster_segments s " +
                                 "JOIN cluster_objects o ON o.generation=s.generation")) {
                            while (result.next()) {
                                UUID segment = (UUID) result.getObject(1);
                                Set<UUID> preferred = new HashSet<>();
                                Set<UUID> hosts = new HashSet<>();
                                for (int index : PlacementPolicy.candidates(segment, nodes, true)) {
                                    if (hosts.add(nodes.faultDomain(index, true))) preferred.add(nodes.node(index).id());
                                    if (preferred.size() == 3) break;
                                }
                                Set<UUID> actual = new HashSet<>();
                                for (Object id : (Object[]) result.getArray(2).getArray()) actual.add((UUID) id);
                                require(actual.equals(preferred), "Segment did not move to preferred hosts");
                                checked++;
                            }
                        }
                        require(checked > 0, "No live segments were checked for placement");
                    }
                    System.out.println("Existing segments balanced across preferred hosts");
                }
                default -> throw new IllegalArgumentException("Unknown test phase");
            }
        }
    }
    private static void put(ClusterStore store, String bucket, String key, byte[] data, boolean createOnly) throws Exception {
        store.put(bucket, key, new ByteArrayInputStream(data), data.length, SigV4.hex(SigV4.hash(data)),
            null, createOnly, "application/octet-stream");
    }
    private static void putPart(ClusterStore store, String id, String bucket, String key, int number, byte[] data)
            throws Exception {
        store.putPart(id, bucket, key, number, new ByteArrayInputStream(data), data.length,
            SigV4.hex(SigV4.hash(data)), null);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
