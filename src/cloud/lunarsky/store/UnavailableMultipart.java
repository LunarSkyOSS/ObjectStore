package cloud.lunarsky.store;

import java.io.InputStream;
import java.util.List;

final class UnavailableMultipart implements MultipartStorage {
    private StoreException unavailable() {
        return new StoreException(501, "NotImplemented", "Multipart uploads are unavailable in the local cluster prototype");
    }
    @Override public String create(String bucket, String key, String contentType) { throw unavailable(); }
    @Override public String putPart(String id, String bucket, String key, int number, InputStream input,
                                    long length, String expectedHash, String checksum) { throw unavailable(); }
    @Override public ObjectStorage.Metadata complete(String id, String bucket, String key, List<Part> parts) { throw unavailable(); }
    @Override public void abort(String id, String bucket, String key) { throw unavailable(); }
    @Override public int activeUploads() { return 0; }
    @Override public long stagedBytes() { return 0; }
}
