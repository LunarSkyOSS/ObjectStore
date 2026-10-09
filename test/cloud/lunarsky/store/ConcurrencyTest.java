package cloud.lunarsky.store;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConcurrencyTest {
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
            System.out.println("Concurrent storage tests passed: atomic overwrite, read and listing after acknowledgement, delete");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
