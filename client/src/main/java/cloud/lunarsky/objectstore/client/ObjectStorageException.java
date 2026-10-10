package cloud.lunarsky.objectstore.client;

import java.io.IOException;

/** An S3 error response with stable fields for callers to inspect. */
public final class ObjectStorageException extends IOException {
    private final int statusCode;
    private final String errorCode;
    private final String requestId;

    ObjectStorageException(int statusCode, String errorCode, String requestId) {
        super("Storage request failed: HTTP " + statusCode + (errorCode == null ? "" : " (" + errorCode + ")"));
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.requestId = requestId;
    }

    /** Returns the HTTP status. */
    public int statusCode() { return statusCode; }

    /** Returns the S3 error code, or {@code null} if the server supplied none. */
    public String errorCode() { return errorCode; }

    /** Returns the request ID, or {@code null}. */
    public String requestId() { return requestId; }

    /** Returns whether retry might help. A failed write may already have reached the server. */
    public boolean retryable() { return statusCode == 429 || statusCode == 500 || statusCode == 502 ||
        statusCode == 503 || statusCode == 504; }
}
