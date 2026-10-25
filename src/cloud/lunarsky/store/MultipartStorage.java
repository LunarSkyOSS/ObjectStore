package cloud.lunarsky.store;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

interface MultipartStorage {
    record Part(int number, String etag, String checksumCrc32) {
        Part(int number, String etag) { this(number, etag, null); }

        void verifyCrc32(long value) {
            if (checksumCrc32 == null) return;
            byte[] bytes = java.nio.ByteBuffer.allocate(4).putInt((int) value).array();
            if (!checksumCrc32.equals(java.util.Base64.getEncoder().encodeToString(bytes)))
                throw new StoreException(400, "BadDigest", "Part CRC32 checksum mismatch");
        }
    }
    record PartInfo(int number, long length, String etag, long modified) {}
    record PartPage(List<PartInfo> parts, int nextMarker, boolean truncated) {}
    record UploadInfo(String id, String key, long created) {}

    String create(String bucket, String key, String contentType,
                  Map<String, String> userMetadata, Map<String, String> tags,
                  Map<String, String> acl) throws IOException;
    default String create(String bucket, String key, String contentType,
                          Map<String, String> userMetadata, Map<String, String> tags) throws IOException {
        return create(bucket, key, contentType, userMetadata, tags, Map.of());
    }
    default String create(String bucket, String key, String contentType) throws IOException {
        return create(bucket, key, contentType, Map.of(), Map.of());
    }
    String putPart(String id, String bucket, String key, int number, InputStream input,
                   long length, String expectedHash, String checksum) throws IOException;
    ObjectStorage.Metadata complete(String id, String bucket, String key, List<Part> parts) throws IOException;
    void abort(String id, String bucket, String key) throws IOException;
    PartPage listParts(String id, String bucket, String key, int marker, int maxParts) throws IOException;
    List<UploadInfo> listUploads(String bucket, String prefix) throws IOException;
    int activeUploads();
    long stagedBytes();
}
