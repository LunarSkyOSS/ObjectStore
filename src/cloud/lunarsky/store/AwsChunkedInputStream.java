package cloud.lunarsky.store;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

final class AwsChunkedInputStream extends FilterInputStream {
    private static final String EMPTY_HASH = SigV4.hex(SigV4.hash(new byte[0]));
    private final SigV4.Verified authorization;
    private final long decodedLength;
    private final String trailerName;
    private final MessageDigest chunkHash;
    private final MessageDigest trailerHash;
    private final Checksum trailerCrc;
    private final XxHashes trailerXxhash;
    private long decoded;
    private long chunkLeft;
    private String suppliedSignature;
    private String previousSignature;
    private boolean finished;
    private String trailerValue;

    AwsChunkedInputStream(InputStream input, SigV4.Verified authorization,
                          long decodedLength, String trailerName) {
        super(input);
        this.authorization = authorization;
        this.decodedLength = decodedLength;
        this.trailerName = trailerName;
        this.previousSignature = authorization.signature();
        try { this.chunkHash = MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        this.trailerCrc = trailerName == null ? null : switch (trailerName) {
            case "x-amz-checksum-crc32" -> new CRC32();
            case "x-amz-checksum-crc32c" -> new CRC32C();
            case "x-amz-checksum-crc64nvme" -> new Crc64Nvme();
            default -> null;
        };
        this.trailerXxhash = trailerName != null && java.util.Set.of("x-amz-checksum-xxhash64",
            "x-amz-checksum-xxhash3", "x-amz-checksum-xxhash128").contains(trailerName)
            ? new XxHashes(trailerName.substring("x-amz-checksum-".length()).toUpperCase(java.util.Locale.ROOT))
            : null;
        String algorithm = trailerName == null ? null : switch (trailerName) {
            case "x-amz-checksum-sha1" -> "SHA-1";
            case "x-amz-checksum-sha256" -> "SHA-256";
            case "x-amz-checksum-sha512" -> "SHA-512";
            case "x-amz-checksum-md5" -> "MD5";
            default -> null;
        };
        if (trailerName != null && trailerCrc == null && trailerXxhash == null && algorithm == null)
            throw new StoreException(501, "NotImplemented", "Checksum trailer is unsupported");
        try { this.trailerHash = algorithm == null ? null : MessageDigest.getInstance(algorithm); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    String trailerValue() { return trailerValue; }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        int count;
        do {
            count = read(one, 0, 1);
        } while (count == 0);
        return count < 0 ? -1 : one[0] & 255;
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) return 0;
        if (finished) return -1;
        if (chunkLeft == 0) nextChunk();
        if (finished) return -1;
        int count = in.read(bytes, offset, (int) Math.min(length, chunkLeft));
        if (count < 0) throw invalid("Incomplete signed chunk");
        if (count == 0) return 0;
        chunkHash.update(bytes, offset, count);
        if (trailerCrc != null) trailerCrc.update(bytes, offset, count);
        if (trailerHash != null) trailerHash.update(bytes, offset, count);
        if (trailerXxhash != null) trailerXxhash.update(bytes, offset, count);
        chunkLeft -= count;
        decoded += count;
        if (chunkLeft == 0) {
            if (!line().isEmpty()) throw invalid("Missing signed chunk separator");
            finishChunk();
        }
        return count;
    }

    private void nextChunk() throws IOException {
        String line = line();
        int separator = line.indexOf(";chunk-signature=");
        if (separator < 1 || separator != line.lastIndexOf(";chunk-signature="))
            throw invalid("Invalid signed chunk header");
        String size = line.substring(0, separator);
        suppliedSignature = line.substring(separator + 17);
        if (!size.matches("[0-9a-fA-F]{1,16}") || !suppliedSignature.matches("[0-9a-f]{64}"))
            throw invalid("Invalid signed chunk header");
        try { chunkLeft = Long.parseUnsignedLong(size, 16); }
        catch (NumberFormatException error) { throw invalid("Invalid signed chunk size"); }
        if (chunkLeft > decodedLength - decoded) throw invalid("Signed chunks exceed decoded length");
        chunkHash.reset();
        if (chunkLeft == 0) {
            finishChunk();
            if (decoded != decodedLength) throw invalid("Decoded length mismatch");
            if (trailerName == null) {
                if (!line().isEmpty()) throw invalid("Invalid signed chunk ending");
            } else {
                String trailer = line();
                if (!trailer.startsWith(trailerName + ":")) throw invalid("Missing signed checksum trailer");
                trailerValue = trailer.substring(trailerName.length() + 1);
                byte[] actual;
                if (trailerCrc != null) {
                    long value = trailerCrc.getValue();
                    actual = new byte[trailerName.equals("x-amz-checksum-crc64nvme") ? 8 : 4];
                    for (int i = actual.length - 1; i >= 0; i--) {
                        actual[i] = (byte) value;
                        value >>>= 8;
                    }
                } else actual = trailerXxhash != null ? trailerXxhash.digest() : trailerHash.digest();
                if (!Base64.getEncoder().encodeToString(actual).equals(trailerValue))
                    throw new StoreException(400, "BadDigest", "Checksum trailer mismatch");
                String signature = line();
                if (!signature.matches("x-amz-trailer-signature=[0-9a-f]{64}"))
                    throw invalid("Missing trailer signature");
                String toSign = "AWS4-HMAC-SHA256-TRAILER\n" + authorization.date() + "\n" +
                    authorization.scope() + "\n" + previousSignature + "\n" +
                    SigV4.hex(SigV4.hash((trailerName + ":" + trailerValue + "\n")
                        .getBytes(StandardCharsets.UTF_8)));
                String expected = SigV4.hex(SigV4.hmac(authorization.signingKey(), toSign));
                if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    signature.substring(24).getBytes(StandardCharsets.US_ASCII)))
                    throw invalid("Trailer signature mismatch");
                if (!line().isEmpty()) throw invalid("Invalid trailer ending");
            }
            if (in.read() != -1) throw invalid("Extra bytes after signed payload");
            finished = true;
        }
    }

    private void finishChunk() throws IOException {
        String toSign = "AWS4-HMAC-SHA256-PAYLOAD\n" + authorization.date() + "\n" +
            authorization.scope() + "\n" + previousSignature + "\n" + EMPTY_HASH + "\n" +
            SigV4.hex(chunkHash.digest());
        byte[] expected = SigV4.hmac(authorization.signingKey(), toSign);
        if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(suppliedSignature)))
            throw invalid("Signed chunk signature mismatch");
        previousSignature = suppliedSignature;
    }

    private String line() throws IOException {
        byte[] bytes = new byte[512];
        int count = 0;
        while (count < bytes.length) {
            int value = in.read();
            if (value < 0) throw invalid("Incomplete signed chunk framing");
            if (value == '\r') {
                if (in.read() != '\n') throw invalid("Invalid signed chunk line ending");
                return new String(bytes, 0, count, StandardCharsets.US_ASCII);
            }
            if (value < 32 || value > 126) throw invalid("Invalid signed chunk line");
            bytes[count++] = (byte) value;
        }
        throw invalid("Signed chunk header is too long");
    }

    private static StoreException invalid(String message) {
        return new StoreException(400, "InvalidRequest", message);
    }
}
