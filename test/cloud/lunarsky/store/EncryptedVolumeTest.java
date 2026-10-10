package cloud.lunarsky.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class EncryptedVolumeTest {
    private static void rejected(Map<String, String> configuration) throws Exception {
        try {
            EncryptedVolume.requireConfigured(configuration);
            throw new AssertionError("Unavailable encrypted storage was accepted");
        } catch (IOException expected) { }
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("objectstore-encrypted-volume-");
        Path marker = directory.resolve(".objectstore-encrypted");
        String id = "0123456789abcdef0123456789abcdef";
        Map<String, String> configuration = Map.of(
            "ENCRYPTED_VOLUME_MARKER_FILE", marker.toString(), "ENCRYPTED_VOLUME_ID", id);
        try {
            EncryptedVolume.requireConfigured(Map.of());
            rejected(configuration);
            Files.writeString(marker, id + "\n");
            EncryptedVolume.requireConfigured(configuration);
            rejected(Map.of("ENCRYPTED_VOLUME_MARKER_FILE", marker.toString()));
            rejected(Map.of("ENCRYPTED_VOLUME_MARKER_FILE", marker.toString(),
                "ENCRYPTED_VOLUME_ID", "fedcba9876543210fedcba9876543210"));
            Files.delete(marker);
            Path elsewhere = directory.resolve("elsewhere");
            Files.writeString(elsewhere, id + "\n");
            Files.createSymbolicLink(marker, elsewhere);
            rejected(configuration);
        } finally {
            Files.deleteIfExists(marker);
            Files.deleteIfExists(directory.resolve("elsewhere"));
            Files.delete(directory);
        }
        System.out.println("Encrypted volume marker tests passed");
    }
}
