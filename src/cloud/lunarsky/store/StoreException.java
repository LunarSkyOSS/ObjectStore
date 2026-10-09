package cloud.lunarsky.store;

final class StoreException extends RuntimeException {
    final int status;
    final String code;
    StoreException(int status, String code, String message) {
        this(status, code, message, null);
    }
    StoreException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }
}
