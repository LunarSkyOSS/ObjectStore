package cloud.lunarsky.store;

import com.sun.net.httpserver.Headers;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.CRC32C;
import java.util.zip.Checksum;

final class UploadChecksums {
    private record Algorithm(String name, String header, int length) {}

    private final byte[] contentMd5;
    private final Algorithm algorithm;
    private final byte[] expected;
    private final String encoded;

    private UploadChecksums(byte[] contentMd5, Algorithm algorithm, byte[] expected, String encoded) {
        this.contentMd5 = contentMd5;
        this.algorithm = algorithm;
        this.expected = expected;
        this.encoded = encoded;
    }

    static UploadChecksums from(Headers headers) {
        String md5 = SigV4.single(headers, "content-md5");
        byte[] contentMd5 = md5 == null ? null : decode(md5, 16);
        Algorithm algorithm = null;
        String encoded = null;
        for (String name : headers.keySet()) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("x-amz-checksum-")) continue;
            if (algorithm != null)
                throw new StoreException(400, "InvalidRequest", "Supply one checksum algorithm");
            algorithm = algorithm(lower);
            encoded = SigV4.single(headers, name);
        }
        String selected = SigV4.single(headers, "x-amz-sdk-checksum-algorithm");
        if (selected != null) {
            String trailer = SigV4.single(headers, "x-amz-trailer");
            if (algorithm == null && trailer != null) {
                Algorithm declared = algorithm(trailer);
                if (!selected.equals(declared.name()))
                    throw new StoreException(400, "InvalidRequest", "Checksum algorithm and trailer must match");
            } else if (algorithm == null || !selected.equals(algorithm.name())) {
                throw new StoreException(400, "InvalidRequest", "Checksum algorithm and value must match");
            }
        }
        byte[] expected = algorithm == null ? null : decode(encoded, algorithm.length());
        return new UploadChecksums(contentMd5, algorithm, expected, encoded);
    }

    private static Algorithm algorithm(String header) {
        return switch (header) {
            case "x-amz-checksum-crc32" -> new Algorithm("CRC32", header, 4);
            case "x-amz-checksum-crc32c" -> new Algorithm("CRC32C", header, 4);
            case "x-amz-checksum-crc64nvme" -> new Algorithm("CRC64NVME", header, 8);
            case "x-amz-checksum-xxhash64" -> new Algorithm("XXHASH64", header, 8);
            case "x-amz-checksum-xxhash3" -> new Algorithm("XXHASH3", header, 8);
            case "x-amz-checksum-xxhash128" -> new Algorithm("XXHASH128", header, 16);
            case "x-amz-checksum-sha1" -> new Algorithm("SHA1", header, 20);
            case "x-amz-checksum-sha256" -> new Algorithm("SHA256", header, 32);
            case "x-amz-checksum-sha512" -> new Algorithm("SHA512", header, 64);
            case "x-amz-checksum-md5" -> new Algorithm("MD5", header, 16);
            default -> throw new StoreException(501, "NotImplemented", "Checksum algorithm is unsupported");
        };
    }

    private static byte[] decode(String value, int length) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == length) return decoded;
        } catch (IllegalArgumentException ignored) { }
        throw new StoreException(400, "InvalidDigest", "Invalid checksum encoding or length");
    }

    String sha256() {
        return algorithm != null && algorithm.name().equals("SHA256") ? encoded : null;
    }

    java.util.Map<String, String> metadata() {
        return algorithm == null ? java.util.Map.of() : java.util.Map.of(algorithm.header(), encoded);
    }

    void response(Headers headers) {
        if (algorithm != null) headers.set(algorithm.header(), encoded);
    }

    InputStream verifying(InputStream input) {
        if (contentMd5 == null && (algorithm == null || algorithm.name().equals("SHA256"))) return input;
        return new VerifiedInput(input);
    }

    private final class VerifiedInput extends FilterInputStream {
        private final MessageDigest md5 = contentMd5 != null ||
            (algorithm != null && algorithm.name().equals("MD5")) ? digest("MD5") : null;
        private final MessageDigest hash = algorithm == null ? null : switch (algorithm.name()) {
            case "SHA1" -> digest("SHA-1");
            case "SHA512" -> digest("SHA-512");
            default -> null;
        };
        private final Checksum crc = algorithm == null ? null : switch (algorithm.name()) {
            case "CRC32" -> new CRC32();
            case "CRC32C" -> new CRC32C();
            case "CRC64NVME" -> new Crc64Nvme();
            default -> null;
        };
        private final XxHashes xxhash = algorithm != null && algorithm.name().startsWith("XXHASH")
            ? new XxHashes(algorithm.name()) : null;
        private boolean checked;

        private VerifiedInput(InputStream input) { super(input); }

        @Override public int read() throws IOException {
            int value = in.read();
            if (value < 0) verify();
            else update(new byte[]{(byte) value}, 0, 1);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length);
            if (count < 0) verify();
            else if (count > 0) update(bytes, offset, count);
            return count;
        }

        private void update(byte[] bytes, int offset, int length) {
            if (md5 != null) md5.update(bytes, offset, length);
            if (hash != null) hash.update(bytes, offset, length);
            if (crc != null) crc.update(bytes, offset, length);
            if (xxhash != null) xxhash.update(bytes, offset, length);
        }

        private void verify() {
            if (checked) return;
            checked = true;
            byte[] actualMd5 = md5 == null ? null : md5.digest();
            if (contentMd5 != null && !MessageDigest.isEqual(contentMd5, actualMd5))
                throw new StoreException(400, "BadDigest", "Content-MD5 mismatch");
            if (algorithm == null || algorithm.name().equals("SHA256")) return;
            byte[] actual;
            if (crc != null) {
                long value = crc.getValue();
                actual = new byte[algorithm.length()];
                for (int i = actual.length - 1; i >= 0; i--) {
                    actual[i] = (byte) value;
                    value >>>= 8;
                }
            } else if (algorithm.name().equals("MD5")) actual = actualMd5;
            else if (xxhash != null) actual = xxhash.digest();
            else actual = hash.digest();
            if (!MessageDigest.isEqual(expected, actual))
                throw new StoreException(400, "BadDigest", algorithm.name() + " checksum mismatch");
        }
    }

    private static MessageDigest digest(String algorithm) {
        try { return MessageDigest.getInstance(algorithm); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
