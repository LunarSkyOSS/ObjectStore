package cloud.lunarsky.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

record NodeIdentity(UUID nodeId, UUID hostId) {
    static NodeIdentity open(Path root, UUID expectedHost) throws IOException {
        Path file = root.resolve("node-identity");
        if (Files.exists(file)) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() != 2) throw new IOException("Invalid node identity file");
            try {
                NodeIdentity identity = new NodeIdentity(UUID.fromString(lines.get(0)), UUID.fromString(lines.get(1)));
                if (!identity.hostId().equals(expectedHost))
                    throw new IOException("Node volume belongs to a different storage host");
                return identity;
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid node identity file", error);
            }
        }
        NodeIdentity identity = new NodeIdentity(UUID.randomUUID(), expectedHost);
        Path temporary = Files.createTempFile(root, ".node-identity-", ".pending");
        try {
            Files.writeString(temporary, identity.nodeId() + "\n" + identity.hostId() + "\n", StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            DiskStore.syncDirectory(root);
            return identity;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
