package cloud.lunarsky.store;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

interface MultipartStorage {
    record Part(int number, String etag) {}

    String create(String bucket, String key, String contentType) throws IOException;
    String putPart(String id, String bucket, String key, int number, InputStream input,
                   long length, String expectedHash, String checksum) throws IOException;
    ObjectStorage.Metadata complete(String id, String bucket, String key, List<Part> parts) throws IOException;
    void abort(String id, String bucket, String key) throws IOException;
    int activeUploads();
    long stagedBytes();
}
