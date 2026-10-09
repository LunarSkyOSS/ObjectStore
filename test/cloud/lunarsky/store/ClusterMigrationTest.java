package cloud.lunarsky.store;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ClusterMigrationTest {
    private static final String KEY = "migration/legacy-object";
    private static final byte[] DATA = "legacy object survives stable-node migration".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        List<URI> urls = Arrays.stream(env.get("CLUSTER_NODES").split(","))
            .map(URI::create).toList();
        String token = env.get("CLUSTER_TOKEN");
        List<NodeClient.Node> addresses = new ArrayList<>();
        for (URI url : urls) {
            NodeIdentity identity = NodeClient.probe(url, token);
            addresses.add(new NodeClient.Node(identity.nodeId(), identity.hostId(), url));
        }
        NodeClient nodes = new NodeClient(addresses, token, null);
        String bucket = env.get("S3_BUCKET");
        if (args[0].equals("create")) {
            UUID segment = UUID.randomUUID();
            byte[] hash = SigV4.hash(DATA);
            for (int i = 0; i < nodes.count(); i++) nodes.put(i, segment, DATA, hash);
            try (var connection = DriverManager.getConnection(env.get("POSTGRES_JDBC_URL"),
                    env.get("POSTGRES_USER"), env.get("POSTGRES_PASSWORD"));
                 var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE cluster_usage (bucket text PRIMARY KEY, used_bytes bigint NOT NULL)");
                statement.execute("CREATE TABLE cluster_objects (bucket text NOT NULL, object_key text COLLATE \"C\" NOT NULL, generation uuid NOT NULL, length bigint NOT NULL, modified bigint NOT NULL, etag text NOT NULL, sha256 bytea NOT NULL, content_type text NOT NULL, PRIMARY KEY (bucket, object_key))");
                statement.execute("CREATE TABLE cluster_segments (generation uuid NOT NULL, ordinal integer NOT NULL, segment_id uuid NOT NULL, length integer NOT NULL, sha256 bytea NOT NULL, replicas text NOT NULL, PRIMARY KEY (generation, ordinal))");
                statement.execute("CREATE TABLE cluster_tombstones (bucket text NOT NULL, object_key text COLLATE \"C\" NOT NULL, generation uuid NOT NULL, deleted_at bigint NOT NULL, PRIMARY KEY (bucket, object_key))");
                try (var insert = connection.prepareStatement("INSERT INTO cluster_usage VALUES (?, ?)")) {
                    insert.setString(1, bucket); insert.setLong(2, DATA.length); insert.executeUpdate();
                }
                UUID generation = UUID.randomUUID();
                try (var insert = connection.prepareStatement("INSERT INTO cluster_objects VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, bucket); insert.setString(2, KEY); insert.setObject(3, generation);
                    insert.setLong(4, DATA.length); insert.setLong(5, System.currentTimeMillis());
                    insert.setString(6, HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(DATA)));
                    insert.setBytes(7, hash); insert.setString(8, "text/plain"); insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("INSERT INTO cluster_segments VALUES (?, 0, ?, ?, ?, '0,1,2')")) {
                    insert.setObject(1, generation); insert.setObject(2, segment);
                    insert.setInt(3, DATA.length); insert.setBytes(4, hash); insert.executeUpdate();
                }
            }
            System.out.println("Legacy cluster fixture created");
            return;
        }
        if (!args[0].equals("verify")) throw new IllegalArgumentException("Use create or verify");
        List<URI> reordered = new ArrayList<>(urls);
        java.util.Collections.reverse(reordered);
        try (ClusterStore store = new ClusterStore(env.get("POSTGRES_JDBC_URL"), env.get("POSTGRES_USER"),
                env.get("POSTGRES_PASSWORD"), bucket, reordered, token, null,
                134217728, 2147483648L, true)) {
            try (var opened = store.open(bucket, KEY)) {
                if (!Arrays.equals(DATA, opened.stream().readAllBytes()))
                    throw new AssertionError("Migrated object changed after node URL reorder");
            }
            store.put(bucket, "migration/new-object", new ByteArrayInputStream(DATA), DATA.length,
                SigV4.hex(SigV4.hash(DATA)), null, false, "text/plain");
        }
        UUID segment;
        try (var connection = DriverManager.getConnection(env.get("POSTGRES_JDBC_URL"),
                env.get("POSTGRES_USER"), env.get("POSTGRES_PASSWORD"));
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT segment_id FROM cluster_segments WHERE replicas='0,1,2' LIMIT 1")) {
            if (!result.next()) throw new AssertionError("Legacy segment missing after migration");
            segment = (UUID) result.getObject(1);
        }
        NodeClient wrong = new NodeClient(List.of(new NodeClient.Node(addresses.get(0).id(),
            addresses.get(0).hostId(), addresses.get(1).url())), token, null);
        try {
            wrong.get(0, segment, DATA.length, SigV4.hash(DATA));
            throw new AssertionError("Node swap was not rejected");
        } catch (java.io.IOException expected) { }
        System.out.println("Stable node migration, reordered gateway config, and wrong-node rejection passed");
    }
}
