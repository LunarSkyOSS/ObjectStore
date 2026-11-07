package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ClusterMaintenanceTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected JDBC URL, user, password");
        Path root = Files.createTempDirectory("objectstore-maintenance-");
        List<ClusterNode> nodes = new ArrayList<>();
        List<HttpServer> servers = new ArrayList<>();
        List<URI> urls = new ArrayList<>();
        String token = "maintenance-test-node-token-0123456789";
        String repair = "maintenance-test-repair-token-0123456";
        try {
            for (int i = 0; i < 3; i++) {
                ClusterNode node = new ClusterNode(root.resolve("node" + i), token, repair, UUID.randomUUID());
                nodes.add(node);
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                servers.add(server);
                server.createContext("/", node::handle);
                server.start();
                urls.add(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            }
            try (ClusterStore store = new ClusterStore(args[0], args[1], args[2], "maintenance-default",
                    urls, token, repair, 1024, 8192, false)) {
                store.createBucket("maintenance-source");
                store.setVersioning("maintenance-source", ObjectStorage.VersioningState.ENABLED);
                byte[] value = new byte[] { 1, 2, 3, 4 };
                for (int i = 0; i < 2; i++)
                    store.put("maintenance-source", "file", new ByteArrayInputStream(value), value.length,
                        null, null, false, "application/octet-stream");
                String upload = store.create("maintenance-source", "part", "application/octet-stream");
                store.putPart(upload, "maintenance-source", "part", 1,
                    new ByteArrayInputStream(value), value.length, null, null);
                check(store.activeUploads() == 1 && store.stagedBytes() == value.length,
                    "Global multipart statistics excluded a non-default bucket");
                renameUnderContention(store, args);
                check(store.listVersions("maintenance-target", "", null, null, 10).entries().size() == 2,
                    "Rename lost retained versions");
                try (var files = Files.walk(root.resolve("node0/segments"))) {
                    for (Path path : files.filter(Files::isRegularFile).toList())
                        Files.write(path, new byte[] { 9 });
                }
                var repaired = store.repairOnce();
                check(repaired.scanned() == 3 && repaired.restored() == 3 && repaired.unrecoverable() == 0,
                    "Repair did not restore committed and staged replicas");
                var status = store.status();
                check(status.metadataWritable() && status.repair().state().equals("completed"),
                    "Completed repair status unavailable");
                check(status.verifiedReplicas().values().stream().mapToLong(Long::longValue).sum() == 9,
                    "Repair replica counts do not match committed and staged replicas");
                try (var opened = store.open("maintenance-target", "file")) {
                    check(java.util.Arrays.equals(value, opened.stream().readAllBytes()), "Repaired data changed");
                }
                for (int i = 0; i < 3; i++) {
                    try (var files = Files.walk(root.resolve("node" + i + "/segments"))) {
                        for (Path path : files.filter(Files::isRegularFile).toList()) Files.delete(path);
                    }
                }
                var lost = store.repairOnce();
                check(lost.scanned() == 3 && lost.unrecoverable() == 3,
                    "Unrecoverable segments were omitted from repair report");
                check(store.status().verifiedReplicas().isEmpty(), "Old replica counts survived the next scan");
                try (ClusterStore second = new ClusterStore(args[0], args[1], args[2], "maintenance-default",
                        urls, token, repair, 1024, 8192, false)) {
                    ConditionalEditTest.exercise(store, second, "conditional-cluster-edit");
                }
            }
            System.out.println("Cluster maintenance tests passed: rename contention, multipart totals, repair status");
        } finally {
            for (HttpServer server : servers) server.stop(0);
            for (ClusterNode node : nodes) node.close();
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void renameUnderContention(ClusterStore store, String[] args) throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
             var blocker = DriverManager.getConnection(args[0], args[1], args[2])) {
            blocker.setAutoCommit(false);
            try (var query = blocker.createStatement()) {
                query.execute("SELECT pg_advisory_xact_lock(6834071092784)");
            }
            var rename = executor.submit(() -> {
                store.renameBucket("maintenance-source", "maintenance-target");
                return null;
            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                try (var observer = DriverManager.getConnection(args[0], args[1], args[2]);
                     var query = observer.createStatement()) {
                    while (System.nanoTime() < deadline && !waiting) {
                        try (var rows = query.executeQuery("SELECT EXISTS (SELECT 1 FROM pg_locks " +
                                "WHERE locktype='advisory' AND NOT granted)")) {
                            rows.next();
                            waiting = rows.getBoolean(1);
                        }
                        if (!waiting) Thread.sleep(10);
                    }
                    check(waiting, "Rename did not wait for the existing mutation lock");
                    query.execute("SET statement_timeout='2s'");
                    query.executeQuery("SELECT count(*) FROM cluster_buckets").close();
                }
            } finally {
                blocker.commit();
            }
            rename.get(5, TimeUnit.SECONDS);
        }
    }
}
