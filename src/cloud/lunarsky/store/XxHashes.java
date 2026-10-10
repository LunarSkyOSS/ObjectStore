package cloud.lunarsky.store;

import com.dynatrace.hash4j.hashing.HashStream64;
import com.dynatrace.hash4j.hashing.HashStream128;
import com.dynatrace.hash4j.hashing.Hashing;
import java.nio.ByteBuffer;

final class XxHashes {
    private final HashStream64 stream;

    XxHashes(String algorithm) {
        stream = switch (algorithm) {
            case "XXHASH64" -> Hashing.xxh64().hashStream();
            case "XXHASH3" -> Hashing.xxh3_64().hashStream();
            case "XXHASH128" -> Hashing.xxh3_128().hashStream();
            default -> throw new IllegalArgumentException(algorithm);
        };
    }

    void update(byte[] bytes, int offset, int length) {
        stream.putBytes(bytes, offset, length);
    }

    byte[] digest() {
        if (stream instanceof HashStream128 wide) {
            var value = wide.get();
            return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array();
        }
        return ByteBuffer.allocate(8).putLong(stream.getAsLong()).array();
    }
}
