package cloud.lunarsky.store;

final class StoreException extends RuntimeException {
    final int status;
    final String code;
    final String versionId;
    final long modified;
    final boolean deleteMarker;
    StoreException(int status, String code, String message) {
        this(status, code, message, null);
    }
    StoreException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.versionId = null;
        this.modified = -1;
        this.deleteMarker = false;
    }
    private StoreException(int status, String code, String message, String versionId, long modified) {
        super(message);
        this.status = status;
        this.code = code;
        this.versionId = versionId;
        this.modified = modified;
        this.deleteMarker = true;
    }
    static StoreException deletedVersion(String versionId, long modified, boolean explicit) {
        return new StoreException(explicit ? 405 : 404, explicit ? "MethodNotAllowed" : "NoSuchKey",
            "Object is deleted", versionId, modified);
    }
}
