package cloud.lunarsky.store;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class DiskBucketNamesTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void missing(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Old bucket name was still available");
        } catch (StoreException error) {
            check(error.status == 404, "Wrong missing-bucket response");
        }
    }

    private static ObjectStorage.Metadata put(DiskBucketNames names, String bucket, String key, String value)
        throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return names.put(bucket, key, new ByteArrayInputStream(bytes), bytes.length,
            null, null, false, "text/plain", Map.of("source", "test"), Map.of());
    }

    private static void run(Path root) throws Exception {
        String old = "renamed-source", next = "renamed-target";
        String firstId;
        String uploadId;
        try (DiskStore disk = new DiskStore(root, 1024, 8192)) {
            disk.createBucket(old);
            DiskBucketNames names = new DiskBucketNames(disk, new MultipartStore(disk));
            names.setVersioning(old, ObjectStorage.VersioningState.ENABLED);
            firstId = put(names, old, "folder/item.txt", "one").versionId();
            put(names, old, "folder/item.txt", "two");
            names.setBucketPolicy(old, "{\"Resource\":\"arn:aws:s3:::renamed-source/*\"}");
            uploadId = names.create(old, "pending.txt", "text/plain");
            names.putPart(uploadId, old, "pending.txt", 1,
                new ByteArrayInputStream("part".getBytes(StandardCharsets.UTF_8)), 4, null, null);
            names.renameBucket(old, next);
            check(names.bucket(next).name().equals(next), "Renamed bucket is not visible");
            check(names.bucket(next).policy().contains("arn:aws:s3:::renamed-target/*"),
                "Policy did not follow the public name");
            check(names.listVersions(next, "", null, null, 100).entries().size() == 2,
                "Retained versions were lost");
            try (var opened = names.open(next, "folder/item.txt", firstId)) {
                check(new String(opened.stream().readAllBytes(), StandardCharsets.UTF_8).equals("one"),
                    "Historical version changed");
                check(opened.metadata().bucket().equals(next), "Metadata exposed the physical name");
            }
            check(names.listUploads(next, "").size() == 1, "Multipart upload did not follow rename");
            missing(() -> { try { names.bucket(old); } catch (java.io.IOException error) {
                throw new RuntimeException(error); } });
            try {
                names.createBucket(old);
                throw new AssertionError("Hidden physical name was reused");
            } catch (StoreException error) {
                check(error.status == 409, "Wrong reused-name response");
            }
        }
        try (DiskStore disk = new DiskStore(root, 1024, 8192)) {
            DiskBucketNames names = new DiskBucketNames(disk, new MultipartStore(disk));
            names.ensureBucket(old);
            check(names.buckets().stream().map(ObjectStorage.Bucket::name).toList().equals(List.of(next)),
                "Public bucket name did not survive restart");
            check(names.listVersions(next, "", null, null, 100).entries().size() == 2,
                "Versions did not survive restart");
            var part = names.listParts(uploadId, next, "pending.txt", 0, 10).parts().getFirst();
            names.complete(uploadId, next, "pending.txt", List.of(new MultipartStorage.Part(1, part.etag())));
            try (var opened = names.open(next, "pending.txt")) {
                check(new String(opened.stream().readAllBytes(), StandardCharsets.UTF_8).equals("part"),
                    "Multipart completion failed after restart");
            }
            names.renameBucket(next, "final-bucket");
            check(names.bucket("final-bucket").policy().contains("arn:aws:s3:::final-bucket/*"),
                "Repeated rename lost policy mapping");
        }
        try (DiskStore disk = new DiskStore(root, 1024, 8192)) {
            DiskBucketNames names = new DiskBucketNames(disk, new MultipartStore(disk));
            check(names.list("final-bucket", "", "", 100, null).objects().size() == 2,
                "Repeated rename lost objects");
        }
    }

    private static void deleteRecovery(Path root) throws Exception {
        String source = "delete-source", target = "delete-target";
        try (DiskStore disk = new DiskStore(root, 1024, 8192)) {
            DiskBucketNames names = new DiskBucketNames(disk, new MultipartStore(disk));
            names.createBucket(source);
            names.renameBucket(source, target);
            String upload = names.create(target, "unfinished.txt", "text/plain");
            try {
                names.deleteBucket(target);
                throw new AssertionError("Bucket with multipart upload was deleted");
            } catch (StoreException expected) {
                check(expected.status == 409, "Wrong active-upload rejection");
            }
            names.abort(upload, target, "unfinished.txt");
            new BucketNameCatalog(disk).beginDelete(target, source);
        }
        try (DiskStore disk = new DiskStore(root, 1024, 8192)) {
            DiskBucketNames names = new DiskBucketNames(disk, new MultipartStore(disk));
            check(names.bucket(target).name().equals(target), "Interrupted deletion lost the bucket");
            new BucketNameCatalog(disk).beginDelete(target, source);
            disk.deleteBucket(source);
        }
        try (DiskStore disk = new DiskStore(root, 1024, 8192)) {
            DiskBucketNames names = new DiskBucketNames(disk, new MultipartStore(disk));
            check(names.buckets().stream().noneMatch(bucket -> bucket.name().equals(target)),
                "Interrupted deletion left a stale alias");
            check(!Files.exists(root.resolve("bucket-name-deletion.txt")), "Deletion journal was retained");
            names.createBucket(source);
            names.renameBucket(source, target);
            names.deleteBucket(target);
            names.createBucket(source);
        }
        try (DiskStore disk = new DiskStore(root, 1024, 8192)) {
            DiskBucketNames names = new DiskBucketNames(disk, new MultipartStore(disk));
            check(names.bucket(source).name().equals(source), "Deleted physical name could not be reused");
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("objectstore-bucket-rename-");
        try {
            run(root);
            deleteRecovery(root);
        }
        finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
        System.out.println("Bucket rename tests passed: versions, multipart, policy, restart");
    }
}
