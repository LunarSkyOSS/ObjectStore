package cloud.lunarsky.store;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConcurrencyTest {
    private static void parallelCapacity() throws Exception {
        Path root = Files.createTempDirectory("store-parallel-capacity-");
        byte[] body = new byte[4096];
        Set<String> saved = new HashSet<>();
        try {
            try (var store = new DiskStore(root, body.length, 8L * body.length);
                 var workers = Executors.newFixedThreadPool(16)) {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<String>> writes = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    String key = "parallel-" + i;
                    writes.add(workers.submit(() -> {
                        start.await();
                        try {
                            store.put("test", key, new ByteArrayInputStream(body), body.length,
                                SigV4.hex(SigV4.hash(body)), null, true, "application/octet-stream");
                            return key;
                        } catch (StoreException error) {
                            if (error.status != 507) throw error;
                            return null;
                        }
                    }));
                }
                start.countDown();
                for (Future<String> write : writes) {
                    String key = write.get();
                    if (key != null) saved.add(key);
                }
                if (saved.size() != 8 || store.usedBytes() != 8L * body.length ||
                    store.list("test", "", "", 100, null).objects().size() != 8)
                    throw new AssertionError("Concurrent writes exceeded capacity or lost an acknowledgement");
            }
            try (var restarted = new DiskStore(root, body.length, 8L * body.length)) {
                if (restarted.usedBytes() != 8L * body.length ||
                    restarted.list("test", "", "", 100, null).objects().size() != 8)
                    throw new AssertionError("Concurrent writes were not recovered after restart");
                for (String key : saved) {
                    try (var object = restarted.open("test", key)) {
                        if (!Arrays.equals(object.stream().readAllBytes(), body))
                            throw new AssertionError("Recovered object differs from acknowledged write");
                    }
                }
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static ObjectStorage.Metadata put(DiskStore store, byte[] body) throws Exception {
        return store.put("test", "shared", new ByteArrayInputStream(body), body.length,
            SigV4.hex(SigV4.hash(body)), null, false, "application/octet-stream");
    }

    private static void check(DiskStore store, byte[] first, byte[] second) throws Exception {
        try (var object = store.open("test", "shared")) {
            byte[] body = object.stream().readAllBytes();
            if (!Arrays.equals(body, first) && !Arrays.equals(body, second))
                throw new AssertionError("Reader observed a partial object");
            if (object.metadata().length() != body.length ||
                !Arrays.equals(object.metadata().sha256(), SigV4.hash(body)))
                throw new AssertionError("Reader observed mismatched metadata");
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("store-concurrency-test-");
        byte[] first = new byte[65536];
        byte[] second = new byte[81920];
        Arrays.fill(first, (byte) 0x35);
        Arrays.fill(second, (byte) 0x67);
        try (var store = new DiskStore(root, 131072, 131072);
             var workers = Executors.newFixedThreadPool(4)) {
            put(store, first);
            var start = new CountDownLatch(1);
            var done = new AtomicBoolean(false);
            Future<?> writer = workers.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 100; i++) {
                        byte[] body = i % 2 == 0 ? second : first;
                        var saved = put(store, body);
                        var listed = store.list("test", "", "", 10, null).objects();
                        if (listed.size() != 1 || !listed.getFirst().metadata().etag().equals(saved.etag()))
                            throw new AssertionError("Acknowledged write is absent from listing");
                        check(store, first, second);
                    }
                } catch (Exception error) {
                    throw new RuntimeException(error);
                } finally {
                    done.set(true);
                }
            });
            Future<?>[] readers = new Future<?>[3];
            for (int i = 0; i < readers.length; i++) {
                readers[i] = workers.submit(() -> {
                    try {
                        start.await();
                        while (!done.get()) check(store, first, second);
                    } catch (Exception error) {
                        throw new RuntimeException(error);
                    }
                });
            }
            start.countDown();
            writer.get();
            for (Future<?> reader : readers) reader.get();
            store.delete("test", "shared");
            if (!store.list("test", "", "", 10, null).objects().isEmpty())
                throw new AssertionError("Acknowledged delete is present in listing");
            try {
                store.open("test", "shared");
                throw new AssertionError("Acknowledged delete remained readable");
            } catch (StoreException expected) {
                if (expected.status != 404) throw expected;
            }
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        parallelCapacity();
        System.out.println("Concurrent storage tests passed: atomic overwrite, read and listing after acknowledgement, delete, parallel quota and restart");
    }
}
