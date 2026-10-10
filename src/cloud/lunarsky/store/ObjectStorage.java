package cloud.lunarsky.store;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Storage operations shared by the local and cluster gateways. */
interface ObjectStorage extends AutoCloseable {
    enum VersioningState { NEVER, ENABLED, SUSPENDED }
    record Limits(long maxObjectBytes, long maxTotalBytes) {}
    record Bucket(String name, long created, VersioningState versioning, Map<String, String> acl) {
        Bucket(String name, long created) { this(name, created, VersioningState.NEVER, Map.of()); }
        Bucket(String name, long created, VersioningState versioning) {
            this(name, created, versioning, Map.of());
        }
    }
    record Metadata(long length, long modified, String etag, byte[] sha256,
                    String bucket, String key, String contentType,
                    Map<String, String> userMetadata, Map<String, String> tags,
                    String versionId, Map<String, String> checksums, Map<String, String> acl) {
        Metadata(long length, long modified, String etag, byte[] sha256,
                 String bucket, String key, String contentType,
                 Map<String, String> userMetadata, Map<String, String> tags,
                 String versionId, Map<String, String> checksums) {
            this(length, modified, etag, sha256, bucket, key, contentType,
                userMetadata, tags, versionId, checksums, Map.of());
        }
        Metadata(long length, long modified, String etag, byte[] sha256,
                 String bucket, String key, String contentType,
                 Map<String, String> userMetadata, Map<String, String> tags,
                 String versionId) {
            this(length, modified, etag, sha256, bucket, key, contentType,
                userMetadata, tags, versionId, Map.of());
        }
        Metadata(long length, long modified, String etag, byte[] sha256,
                 String bucket, String key, String contentType,
                 Map<String, String> userMetadata, Map<String, String> tags) {
            this(length, modified, etag, sha256, bucket, key, contentType, userMetadata, tags, null);
        }
        Metadata(long length, long modified, String etag, byte[] sha256,
                 String bucket, String key, String contentType) {
            this(length, modified, etag, sha256, bucket, key, contentType, Map.of(), Map.of(), null);
        }
    }
    record OpenObject(Metadata metadata, InputStream stream) implements AutoCloseable {
        public void close() throws IOException { stream.close(); }
    }
    record ListedObject(String key, Metadata metadata) {}
    record ListPage(List<ListedObject> objects, List<String> prefixes, String nextKey, boolean truncated) {
        int keyCount() { return objects.size() + prefixes.size(); }
    }
    record VersionEntry(String key, String versionId, long modified, boolean deleteMarker,
                        boolean latest, Metadata metadata) {}
    record VersionPage(List<VersionEntry> entries, String nextKey, String nextVersionId,
                       boolean truncated) {}
    record DeleteResult(String versionId, boolean deleteMarker) {}

    Metadata put(String bucket, String key, InputStream input, long length, String expectedHash,
                 String checksum, boolean createOnly, String contentType,
                 Map<String, String> userMetadata, Map<String, String> tags,
                 java.util.function.Supplier<Map<String, String>> checksums,
                 Map<String, String> acl) throws IOException;
    default Metadata put(String bucket, String key, InputStream input, long length, String expectedHash,
                         String checksum, boolean createOnly, String contentType,
                         Map<String, String> userMetadata, Map<String, String> tags,
                         java.util.function.Supplier<Map<String, String>> checksums) throws IOException {
        return put(bucket, key, input, length, expectedHash, checksum, createOnly, contentType,
            userMetadata, tags, checksums, Map.of());
    }
    default Metadata put(String bucket, String key, InputStream input, long length, String expectedHash,
                         String checksum, boolean createOnly, String contentType,
                         Map<String, String> userMetadata, Map<String, String> tags) throws IOException {
        return put(bucket, key, input, length, expectedHash, checksum, createOnly, contentType,
            userMetadata, tags, Map::of);
    }
    default Metadata put(String bucket, String key, InputStream input, long length, String expectedHash,
                         String checksum, boolean createOnly, String contentType) throws IOException {
        return put(bucket, key, input, length, expectedHash, checksum, createOnly, contentType, Map.of(), Map.of());
    }
    OpenObject open(String bucket, String key) throws IOException;
    default OpenObject open(String bucket, String key, String versionId) throws IOException {
        if (versionId == null) return open(bucket, key);
        throw new StoreException(501, "NotImplemented", "Object versioning is unavailable");
    }
    Map<String, String> tags(String bucket, String key) throws IOException;
    default Map<String, String> tags(String bucket, String key, String versionId) throws IOException {
        if (versionId == null) return tags(bucket, key);
        throw new StoreException(501, "NotImplemented", "Versioned tagging is unavailable");
    }
    void setTags(String bucket, String key, Map<String, String> tags) throws IOException;
    default void setTags(String bucket, String key, String versionId,
                         Map<String, String> tags) throws IOException {
        if (versionId == null) setTags(bucket, key, tags);
        else throw new StoreException(501, "NotImplemented", "Versioned tagging is unavailable");
    }
    void delete(String bucket, String key) throws IOException;
    default DeleteResult delete(String bucket, String key, String versionId) throws IOException {
        if (versionId != null) throw new StoreException(501, "NotImplemented", "Object versioning is unavailable");
        delete(bucket, key);
        return new DeleteResult(null, false);
    }
    default void setVersioning(String bucket, VersioningState state) throws IOException {
        throw new StoreException(501, "NotImplemented", "Object versioning is unavailable");
    }
    default VersionPage listVersions(String bucket, String prefix, String keyMarker,
                                     String versionMarker, int maxKeys) throws IOException {
        throw new StoreException(501, "NotImplemented", "Object versioning is unavailable");
    }
    ListPage list(String bucket, String prefix, String delimiter, int maxKeys, String after) throws IOException;
    void ensureBucket(String bucket) throws IOException;
    Bucket bucket(String bucket) throws IOException;
    List<Bucket> buckets() throws IOException;
    void createBucket(String bucket) throws IOException;
    void deleteBucket(String bucket) throws IOException;
    void setBucketAcl(String bucket, Map<String, String> acl) throws IOException;
    void setObjectAcl(String bucket, String key, String versionId, Map<String, String> acl) throws IOException;
    Limits limits();
    default boolean ready() { return true; }
    void close() throws IOException;
}
