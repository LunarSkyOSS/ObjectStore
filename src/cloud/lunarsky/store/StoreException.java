package cloud.lunarsky.store;

final class StoreException extends RuntimeException {
    final int status;
    final String code;
    StoreException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
