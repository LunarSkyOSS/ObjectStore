package cloud.lunarsky.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class EncryptedVolume {
    private EncryptedVolume() {}

    static void requireConfigured(Map<String, String> environment) throws IOException {
        requireConfigured(environment, null);
    }

    static void requireConfigured(Map<String, String> environment, Path dataDirectory) throws IOException {
        String marker = environment.getOrDefault("ENCRYPTED_VOLUME_MARKER_FILE", "");
        String expected = environment.getOrDefault("ENCRYPTED_VOLUME_ID", "");
        if (marker.isEmpty() && expected.isEmpty()) return;
        if (!expected.matches("[a-f0-9]{32}") || marker.isEmpty())
            throw new IOException("Encrypted storage marker configuration is incomplete");
        Path path = Path.of(marker);
        if (!path.isAbsolute() || dataDirectory != null &&
            !path.normalize().equals(dataDirectory.toAbsolutePath().normalize().resolve(".objectstore-encrypted")) ||
            Files.isSymbolicLink(path) || !Files.isRegularFile(path) ||
            Files.size(path) > 33 || !Files.readString(path, StandardCharsets.US_ASCII).trim().equals(expected))
            throw new IOException("Encrypted storage volume is unavailable or does not match its marker");
    }
}
