package cloud.lunarsky.objectstore.client;

import java.io.IOException;

/** A successful HTTP response that does not match the expected storage protocol. */
public final class ProtocolException extends IOException {
    ProtocolException(String message) { super(message); }
    ProtocolException(String message, Throwable cause) { super(message, cause); }
}
