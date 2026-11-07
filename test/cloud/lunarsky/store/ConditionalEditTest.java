package cloud.lunarsky.store;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ConditionalEditTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static ObjectStorage.Metadata put(ObjectStorage store, String bucket, String key, String text)
            throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return store.put(bucket, key, new ByteArrayInputStream(bytes), bytes.length, null, null, false,
            "text/plain", Map.of("source", "editor-test"), Map.of("purpose", "test"));
    }

    private static ObjectStorage.Metadata head(ObjectStorage store, String bucket, String key) throws Exception {
        try (var opened = store.open(bucket, key)) { return opened.metadata(); }
    }

    private static boolean replace(ObjectStorage store, String bucket, String key,
                                   ObjectStorage.Metadata expected, String text) throws Exception {
        try {
            store.replaceVersion(bucket, key, text.getBytes(StandardCharsets.UTF_8), expected);
            return true;
        } catch (StoreException conflict) {
            check(conflict.status == 412, "Wrong conditional-edit rejection");
            return false;
        }
    }

    static void exercise(ObjectStorage first, ObjectStorage second, String bucket) throws Exception {
        first.createBucket(bucket);
        put(first, bucket, "text", "original");
        first.setVersioning(bucket, ObjectStorage.VersioningState.ENABLED);
        check(replace(first, bucket, "text", head(first, bucket, "text"), "enabled"),
            "Could not edit an object written before versioning was enabled");
        var original = head(first, bucket, "text");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> {
                ready.countDown();
                start.await();
                return replace(first, bucket, "text", original, "first");
            });
            var b = executor.submit(() -> {
                ready.countDown();
                start.await();
                return replace(second, bucket, "text", original, "second");
            });
            check(ready.await(5, TimeUnit.SECONDS), "Editors did not start");
            start.countDown();
            check(a.get(10, TimeUnit.SECONDS) != b.get(10, TimeUnit.SECONDS),
                "Both concurrent editors succeeded, or neither did");
        }
        var current = head(first, bucket, "text");
        check(current.userMetadata().equals(original.userMetadata()) && current.tags().equals(original.tags()),
            "Conditional edit lost object attributes");
        check(!replace(second, bucket, "text", original, "stale"), "Stale edit overwrote newer data");
        second.setTags(bucket, "text", Map.of("purpose", "changed"));
        check(!replace(first, bucket, "text", current, "stale tags"), "Edit reverted a concurrent tag update");
        current = head(first, bucket, "text");
        second.setObjectAcl(bucket, "text", current.versionId(), Map.of("owner", "updated-owner"));
        check(!replace(first, bucket, "text", current, "stale ACL"), "Edit reverted a concurrent ACL update");
        current = head(first, bucket, "text");
        first.setVersioning(bucket, ObjectStorage.VersioningState.SUSPENDED);
        check(!replace(second, bucket, "text", current, "unsafe"), "Edit succeeded after versioning was suspended");
        put(first, bucket, "other", "old null version");
        var previousNull = head(first, bucket, "other");
        put(second, bucket, "other", "new null version");
        first.setVersioning(bucket, ObjectStorage.VersioningState.ENABLED);
        check(!replace(first, bucket, "other", previousNull, "stale null version"),
            "Reused null version ID bypassed the content check");
        first.delete(bucket, "text");
        check(!replace(second, bucket, "text", current, "resurrected"), "Edit replaced a delete marker");
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("objectstore-conditional-edit-");
        try (DiskStore disk = new DiskStore(root, 1024, 8192)) {
            DiskBucketNames names = new DiskBucketNames(disk, new MultipartStore(disk));
            exercise(names, names, "conditional-edit");
            names.renameBucket("conditional-edit", "edited-rename");
            var expected = head(names, "edited-rename", "other");
            check(replace(names, "edited-rename", "other", expected, "renamed"),
                "Conditional edit did not resolve a renamed bucket");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("Conditional edit tests passed: concurrent saves, versioning changes, renamed buckets");
    }
}
