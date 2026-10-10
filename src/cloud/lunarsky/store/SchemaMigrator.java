package cloud.lunarsky.store;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class SchemaMigrator {
    private SchemaMigrator() {}

    static int prepare(Connection connection, String bucket) throws IOException {
        try {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SELECT pg_advisory_xact_lock(6834071092781)");
                statement.execute("CREATE TABLE IF NOT EXISTS cluster_schema_migrations (version integer PRIMARY KEY)");
                int version;
                try (ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM cluster_schema_migrations")) {
                    result.next();
                    version = result.getInt(1);
                }
                if (version > 3) throw new IOException("Metadata schema is newer than this ObjectStore build");
                if (version < 1) {
                    statement.execute("CREATE TABLE IF NOT EXISTS cluster_usage (bucket text PRIMARY KEY, used_bytes bigint NOT NULL CHECK (used_bytes >= 0))");
                    statement.execute("CREATE TABLE IF NOT EXISTS cluster_objects (bucket text NOT NULL, object_key text COLLATE \"C\" NOT NULL, generation uuid NOT NULL, length bigint NOT NULL, modified bigint NOT NULL, etag text NOT NULL, sha256 bytea NOT NULL, content_type text NOT NULL, PRIMARY KEY (bucket, object_key))");
                    statement.execute("CREATE TABLE IF NOT EXISTS cluster_segments (generation uuid NOT NULL, ordinal integer NOT NULL, segment_id uuid NOT NULL, length integer NOT NULL, sha256 bytea NOT NULL, replicas text NOT NULL, PRIMARY KEY (generation, ordinal))");
                    statement.execute("CREATE TABLE IF NOT EXISTS cluster_tombstones (bucket text NOT NULL, object_key text COLLATE \"C\" NOT NULL, generation uuid NOT NULL, deleted_at bigint NOT NULL, PRIMARY KEY (bucket, object_key))");
                    statement.execute("INSERT INTO cluster_schema_migrations VALUES (1)");
                }
                if (version < 2) {
                    statement.execute("ALTER TABLE cluster_segments ADD COLUMN IF NOT EXISTS replica_ids uuid[]");
                    statement.execute("ALTER TABLE cluster_segments ADD COLUMN IF NOT EXISTS placement_version bigint NOT NULL DEFAULT 0");
                    statement.execute("ALTER TABLE cluster_segments ADD CONSTRAINT cluster_replica_ids_required CHECK (replica_ids IS NOT NULL) NOT VALID");
                    statement.execute("CREATE TABLE IF NOT EXISTS cluster_nodes (node_id uuid PRIMARY KEY, host_id uuid NOT NULL, endpoint text NOT NULL UNIQUE, legacy_index integer UNIQUE, state text NOT NULL CHECK (state IN ('joining','active','draining','offline','retired')))");
                    statement.execute("CREATE TABLE IF NOT EXISTS cluster_format (singleton integer PRIMARY KEY CHECK (singleton=1), version integer NOT NULL)");
                    statement.execute("INSERT INTO cluster_schema_migrations VALUES (2)");
                }
                if (version < 3) {
                    statement.execute("CREATE TABLE cluster_uploads (upload_id uuid PRIMARY KEY, bucket text NOT NULL, object_key text COLLATE \"C\" NOT NULL, content_type text NOT NULL, created_at bigint NOT NULL)");
                    statement.execute("CREATE TABLE cluster_upload_parts (upload_id uuid NOT NULL REFERENCES cluster_uploads(upload_id) ON DELETE CASCADE, part_number integer NOT NULL CHECK (part_number BETWEEN 1 AND 10000), length bigint NOT NULL CHECK (length >= 0), etag text NOT NULL, modified bigint NOT NULL, PRIMARY KEY (upload_id, part_number))");
                    statement.execute("CREATE TABLE cluster_upload_segments (upload_id uuid NOT NULL, part_number integer NOT NULL, ordinal integer NOT NULL, segment_id uuid NOT NULL, length integer NOT NULL, sha256 bytea NOT NULL, replica_ids uuid[] NOT NULL, placement_version bigint NOT NULL DEFAULT 0, PRIMARY KEY (upload_id, part_number, ordinal), FOREIGN KEY (upload_id, part_number) REFERENCES cluster_upload_parts(upload_id, part_number) ON DELETE CASCADE)");
                    statement.execute("INSERT INTO cluster_schema_migrations VALUES (3)");
                }
                statement.execute("INSERT INTO cluster_format SELECT 1, CASE WHEN EXISTS (SELECT 1 FROM cluster_segments WHERE replica_ids IS NULL) THEN 1 ELSE 2 END WHERE NOT EXISTS (SELECT 1 FROM cluster_format)");
            }
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO cluster_usage VALUES (?, 0) ON CONFLICT DO NOTHING")) {
                insert.setString(1, bucket);
                insert.executeUpdate();
            }
            int format;
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT version FROM cluster_format WHERE singleton=1")) {
                if (!result.next()) throw new IOException("Missing cluster format marker");
                format = result.getInt(1);
            }
            if (format < 1 || format > 2) throw new IOException("Unsupported cluster data format " + format);
            if (format == 2) {
                try (Statement statement = connection.createStatement();
                     ResultSet result = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM cluster_segments WHERE replica_ids IS NULL)")) {
                    result.next();
                    if (result.getBoolean(1)) throw new IOException("Cluster format has unmigrated segment replicas");
                }
            }
            connection.commit();
            return format;
        } catch (SQLException | IOException error) {
            try { connection.rollback(); }
            catch (SQLException rollback) { error.addSuppressed(rollback); }
            if (error instanceof IOException io) throw io;
            throw new IOException("Metadata schema migration failed", error);
        } finally {
            try { connection.setAutoCommit(true); }
            catch (SQLException error) { throw new IOException("Could not restore metadata connection", error); }
        }
    }
}
