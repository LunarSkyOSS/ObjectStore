package cloud.lunarsky.store;

import java.net.URI;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

public final class ClusterBucketRenameTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static long count(java.sql.Connection connection, String table, String bucket) throws Exception {
        try (var query = connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE bucket=?")) {
            query.setString(1, bucket);
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected JDBC URL, user, password");
        String url = args[0], user = args[1], password = args[2];
        String configured = "rename-default", source = "rename-source", target = "rename-target";
        List<URI> nodes = List.of(URI.create("http://127.0.0.1:19091"),
            URI.create("http://127.0.0.1:19092"));
        try (var connection = DriverManager.getConnection(url, user, password)) {
            SchemaMigrator.prepare(connection, configured);
            try (var insert = connection.prepareStatement(
                "INSERT INTO cluster_nodes (node_id,host_id,endpoint,state) VALUES (?,?,?,'active')")) {
                for (URI node : nodes) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setObject(2, UUID.randomUUID());
                    insert.setString(3, node.toString());
                    insert.executeUpdate();
                }
            }
        }
        try (ClusterStore store = new ClusterStore(url, user, password, configured, nodes,
            "cluster-rename-test-token-1234567890123456", null, 1024, 8192, true)) {
            store.createBucket(source);
            store.createBucket("rename-taken");
            store.setBucketPolicy(source, "{\"Resource\":\"arn:aws:s3:::rename-source/*\"}");
            try (var connection = DriverManager.getConnection(url, user, password)) {
                UUID generation = UUID.randomUUID();
                try (var insert = connection.prepareStatement(
                    "INSERT INTO cluster_object_versions (bucket,object_key,version_id,delete_marker," +
                    "generation,length,modified,etag,sha256,content_type) VALUES (?,?,?,false,?,?,?,?,?,?)")) {
                    insert.setString(1, source);
                    insert.setString(2, "key.txt");
                    insert.setString(3, UUID.randomUUID().toString());
                    insert.setObject(4, generation);
                    insert.setLong(5, 4);
                    insert.setLong(6, System.currentTimeMillis());
                    insert.setString(7, "0".repeat(32));
                    insert.setBytes(8, new byte[32]);
                    insert.setString(9, "text/plain");
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement(
                    "INSERT INTO cluster_object_heads (bucket,object_key,version_id) " +
                    "SELECT bucket,object_key,version_id FROM cluster_object_versions WHERE bucket=?")) {
                    insert.setString(1, source);
                    insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement(
                    "INSERT INTO cluster_uploads (upload_id,bucket,object_key,content_type,created_at) " +
                    "VALUES (?,?,?,?,?)")) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, source);
                    insert.setString(3, "pending.txt");
                    insert.setString(4, "text/plain");
                    insert.setLong(5, System.currentTimeMillis());
                    insert.executeUpdate();
                }
            }
            try {
                store.renameBucket(source, "rename-taken");
                throw new AssertionError("Duplicate target was accepted");
            } catch (StoreException expected) {
                check(expected.status == 409, "Wrong duplicate-name status");
            }
            check(store.bucket(source).name().equals(source), "Rejected rename changed source");
            store.renameBucket(source, target);
            check(store.bucket(target).policy().contains("arn:aws:s3:::rename-target/*"),
                "Policy did not follow the rename");
            try {
                store.bucket(source);
                throw new AssertionError("Old name remained available");
            } catch (StoreException expected) {
                check(expected.status == 404, "Wrong old-name response");
            }
            try (var connection = DriverManager.getConnection(url, user, password)) {
                for (String table : List.of("cluster_usage", "cluster_object_versions",
                    "cluster_object_heads", "cluster_uploads")) {
                    check(count(connection, table, target) == 1, table + " was not renamed");
                    check(count(connection, table, source) == 0, table + " retained the old name");
                }
            }
        }
        try (ClusterStore store = new ClusterStore(url, user, password, configured, nodes,
            "cluster-rename-test-token-1234567890123456", null, 1024, 8192, true)) {
            check(store.bucket(target).name().equals(target), "Rename did not survive restart");
        }
        try {
            new ClusterStore(url, user, password, source, nodes,
                "cluster-rename-test-token-1234567890123456", null, 1024, 8192, true).close();
            throw new AssertionError("Old default bucket was recreated");
        } catch (java.io.IOException expected) {
            check(expected.getMessage().contains("S3_BUCKET"), "Wrong stale-config failure");
        }
        System.out.println("Cluster bucket rename tests passed");
    }
}
