package cloud.lunarsky.store;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class AdminOperationsTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static AdminBulkJobs.Snapshot await(AdminBulkJobs jobs, String id) throws Exception {
        for (int retry = 0; retry < 200; retry++) {
            AdminBulkJobs.Snapshot snapshot = jobs.get(id);
            if (snapshot.finished() != 0) return snapshot;
            Thread.sleep(25);
        }
        throw new AssertionError("Bulk job did not finish");
    }

    private static void copySafety(DiskStore store) throws Exception {
        byte[] contents = "private destination".getBytes(StandardCharsets.UTF_8);
        store.setBucketOwnership("source-bucket", ObjectStorage.ObjectOwnership.OBJECT_WRITER);
        store.setPublicAccessBlock("source-bucket", ObjectStorage.PublicAccessBlock.open());
        store.put("source-bucket", "shared.txt", new ByteArrayInputStream(contents), contents.length,
            null, null, false, "text/plain", Map.of("purpose", "test"), Map.of("empty", ""),
            Map::of, Map.of(Acl.ALL_USERS, Integer.toString(Acl.READ)));
        AdminBulkJobs jobs = new AdminBulkJobs(store);
        var copy = jobs.start(new AdminBulkJobs.Spec("copy", "source-bucket", "shared.txt",
            "target-bucket", "private.txt", "", ""));
        check(await(jobs, copy.id()).succeeded() == 1, "Private bulk copy failed");
        try (var opened = store.open("target-bucket", "private.txt")) {
            check(opened.metadata().acl().isEmpty(), "Bulk copy carried source ACL into destination");
            check(opened.metadata().userMetadata().get("purpose").equals("test") &&
                opened.metadata().tags().containsKey("empty"), "Bulk copy lost metadata or tags");
        }

        ObjectStorage corrupting = (ObjectStorage) Proxy.newProxyInstance(
            ObjectStorage.class.getClassLoader(), new Class<?>[]{ObjectStorage.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("open")) {
                    try (var original = store.open((String) arguments[0], (String) arguments[1])) {
                        byte[] corrupted = original.stream().readAllBytes();
                        corrupted[0] ^= 1;
                        return new ObjectStorage.OpenObject(original.metadata(), new ByteArrayInputStream(corrupted));
                    }
                }
                try { return method.invoke(store, arguments); }
                catch (InvocationTargetException error) { throw error.getCause(); }
            });
        AdminBulkJobs corruptJobs = new AdminBulkJobs(corrupting);
        var corrupted = corruptJobs.start(new AdminBulkJobs.Spec("copy", "source-bucket", "shared.txt",
            "target-bucket", "corrupt.txt", "", ""));
        check(await(corruptJobs, corrupted.id()).failed() == 1, "Corrupted bulk copy was accepted");
        check(store.list("target-bucket", "corrupt.txt", "", 1, null).objects().isEmpty(),
            "Corrupted bulk copy installed an object");
    }

    private static void emptyTagValue(DiskStore store) throws Exception {
        AdminActions.apply(store, "object-tag", Map.of("bucket", "target-bucket", "key", "copy-000",
            "operation", "add", "tag", "empty", "value", ""), Set.of(), null);
        check("".equals(store.tags("target-bucket", "copy-000").get("empty")),
            "Admin tag editor rejected an empty tag value");
        Map<String, String> tags = new HashMap<>();
        for (int i = 0; i < 10; i++) tags.put("tag-" + i, "");
        store.setTags("target-bucket", "copy-000", tags);
        try {
            AdminActions.apply(store, "object-tag", Map.of("bucket", "target-bucket", "key", "copy-000",
                "operation", "add", "tag", "extra", "value", ""), Set.of(), null);
            throw new AssertionError("Admin accepted more than ten tags");
        } catch (StoreException expected) {
            check(expected.status == 400 && expected.code.equals("InvalidTag"), "Unexpected tag failure");
        }
        AdminBulkJobs jobs = new AdminBulkJobs(store);
        var tag = jobs.start(new AdminBulkJobs.Spec("tag", "target-bucket", "copy-000",
            "", "", "extra", ""));
        check(await(jobs, tag.id()).failed() == 1, "Bulk job accepted more than ten tags");
        check(store.tags("target-bucket", "copy-000").equals(tags), "Rejected tagging changed the object");
    }

    private static void staleEditorSave(DiskStore store) throws Exception {
        store.createBucket("editor-bucket");
        store.setVersioning("editor-bucket", ObjectStorage.VersioningState.ENABLED);
        byte[] original = "original".getBytes(StandardCharsets.UTF_8);
        ObjectStorage.Metadata metadata = store.put("editor-bucket", "note.txt",
            new ByteArrayInputStream(original), original.length, null, null, false, "text/plain");
        byte[] concurrent = "concurrent update".getBytes(StandardCharsets.UTF_8);
        ObjectStorage racing = (ObjectStorage) Proxy.newProxyInstance(
            ObjectStorage.class.getClassLoader(), new Class<?>[]{ObjectStorage.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("replaceVersion"))
                    store.put("editor-bucket", "note.txt", new ByteArrayInputStream(concurrent),
                        concurrent.length, null, null, false, "text/plain");
                try { return method.invoke(store, arguments); }
                catch (InvocationTargetException error) { throw error.getCause(); }
            });
        try {
            AdminTextEditor.save(racing, Map.of("bucket", "editor-bucket", "key", "note.txt",
                "originalVersion", metadata.versionId(), "originalSha256", SigV4.hex(metadata.sha256()),
                "content", "stale editor content"));
            throw new AssertionError("Stale editor save overwrote a concurrent update");
        } catch (StoreException expected) {
            check(expected.status == 409 && expected.code.equals("Conflict"), "Unexpected editor conflict");
        }
        try (var opened = store.open("editor-bucket", "note.txt")) {
            check(new String(opened.stream().readAllBytes(), StandardCharsets.UTF_8).equals("concurrent update"),
                "Editor replaced the concurrent update");
        }
        check(store.listVersions("editor-bucket", "note.txt", null, null, 10).entries().size() == 2,
            "Rejected editor save created a new version");
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("admin-operations-");
        try {
            AdminAuditTrail audit = new AdminAuditTrail(root);
            audit.record("create-bucket", "source", "started", "127.0.0.1");
            audit.record("create-bucket", "source", "succeeded", "127.0.0.1");
            check(new AdminAuditTrail(root).recent(1).getFirst().result().equals("succeeded"),
                "Audit event did not survive restart");
            try (DiskStore store = new DiskStore(root.resolve("data"), 1024, 1024 * 1024)) {
                store.createBucket("source-bucket");
                store.createBucket("target-bucket");
                byte[] value = "hello".getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 225; i++)
                    store.put("source-bucket", "item-" + String.format("%03d", i),
                        new ByteArrayInputStream(value), value.length, null, null, false,
                        "text/plain", Map.of(), Map.of());
                S3Access access = new S3Access(store, "OWNERACCESSKEY123");
                AdminAccessCheck checker = new AdminAccessCheck(store, access,
                    KeyScopes.unrestricted("OWNERACCESSKEY123"),
                    Set.of("OWNERACCESSKEY123", "OTHERACCESSKEY123"), null);
                check(checker.check("OWNERACCESSKEY123", "read", "source-bucket", "item-000").allowed(),
                    "Owner should read a current object");
                check(!checker.check("OTHERACCESSKEY123", "read", "source-bucket", "item-000").allowed(),
                    "Unrelated key should not read an object");
                check(!checker.check("OWNERACCESSKEY123", "read", "source-bucket", "missing").allowed(),
                    "Missing object should not read as allowed");
                AdminBulkJobs jobs = new AdminBulkJobs(store);
                var copy = jobs.start(new AdminBulkJobs.Spec("copy", "source-bucket", "item-",
                    "target-bucket", "copy-", "", ""));
                check(await(jobs, copy.id()).succeeded() == 225, "Paged copy missed objects");
                try (var opened = store.open("target-bucket", "copy-224")) {
                    check(new String(opened.stream().readAllBytes(), StandardCharsets.UTF_8).equals("hello"),
                        "Copied data changed");
                }
                var tag = jobs.start(new AdminBulkJobs.Spec("tag", "target-bucket", "copy-",
                    "", "", "group", "example"));
                check(await(jobs, tag.id()).succeeded() == 225, "Paged tag missed objects");
                check(store.tags("target-bucket", "copy-224").get("group").equals("example"),
                    "Tag not persisted");
                var inventory = jobs.start(new AdminBulkJobs.Spec("inventory", "target-bucket", "copy-",
                    "", "", "", ""));
                check(await(jobs, inventory.id()).processed() == 225 &&
                    jobs.report(inventory.id()).contains("copy-224"), "Inventory report incomplete");
                var deletion = jobs.start(new AdminBulkJobs.Spec("delete", "source-bucket", "item-",
                    "", "", "", ""));
                check(await(jobs, deletion.id()).succeeded() == 225 &&
                    store.list("source-bucket", "", "", 10, null).objects().isEmpty(),
                    "Paged delete missed objects");
                copySafety(store);
                emptyTagValue(store);
                staleEditorSave(store);
                System.out.println("Admin operations tests passed: audit, access, paged jobs");
            }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
