package cloud.lunarsky.store;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Storage operations shared by the local and cluster gateways. */
interface ObjectStorage extends AutoCloseable {
    record Metadata(long length, long modified, String etag, byte[] sha256,
                    String bucket, String key, String contentType) {}
    record OpenObject(Metadata metadata, InputStream stream) implements AutoCloseable {
        public void close() throws IOException { stream.close(); }
    }
    record ListedObject(String key, Metadata metadata) {}
    record ListPage(List<ListedObject> objects, List<String> prefixes, String nextKey, boolean truncated) {
        int keyCount() { return objects.size() + prefixes.size(); }
    }

    Metadata put(String bucket, String key, InputStream input, long length, String expectedHash,
                 String checksum, boolean createOnly, String contentType) throws IOException;
    OpenObject open(String bucket, String key) throws IOException;
    void delete(String bucket, String key) throws IOException;
    ListPage list(String bucket, String prefix, String delimiter, int maxKeys, String after) throws IOException;
    default boolean ready() { return true; }
    void close() throws IOException;
}
