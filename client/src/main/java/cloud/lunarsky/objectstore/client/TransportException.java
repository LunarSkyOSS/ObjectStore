package cloud.lunarsky.objectstore.client;

import java.io.IOException;

/** A connection or I/O failure. A write may have completed before this was observed. */
public final class TransportException extends IOException {
    TransportException(IOException cause) { super("Storage transport failed", cause); }
}
