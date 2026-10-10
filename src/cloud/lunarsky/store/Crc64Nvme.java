package cloud.lunarsky.store;

import java.util.zip.Checksum;
import java.util.Base64;
import java.nio.ByteBuffer;

final class Crc64Nvme implements Checksum {
    private static final long POLYNOMIAL = 0x9a6c9329ac4bc9b5L;
    private static final long[] TABLE = table();
    private long state = -1L;

    private static long[] table() {
        long[] values = new long[256];
        for (int index = 0; index < values.length; index++) {
            long value = index;
            for (int bit = 0; bit < 8; bit++)
                value = (value >>> 1) ^ ((value & 1L) == 0 ? 0 : POLYNOMIAL);
            values[index] = value;
        }
        return values;
    }

    @Override public void update(int value) {
        state = (state >>> 8) ^ TABLE[(int) (state ^ value) & 255];
    }

    @Override public void update(byte[] bytes, int offset, int length) {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        for (int i = offset; i < offset + length; i++) update(bytes[i]);
    }

    @Override public long getValue() { return ~state; }

    String encoded() {
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(8).putLong(getValue()).array());
    }

    @Override public void reset() { state = -1L; }
}
