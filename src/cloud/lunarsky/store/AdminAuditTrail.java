package cloud.lunarsky.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class AdminAuditTrail {
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private final Path file;
    private final Path previous;

    record Entry(long timeMillis, String action, String target, String result, String peer) { }

    AdminAuditTrail(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        file = dataDirectory.resolve("admin-audit.tsv");
        previous = dataDirectory.resolve("admin-audit.previous.tsv");
        if (!Files.exists(file)) Files.createFile(file);
        privateFile(file);
    }

    synchronized void record(String action, String target, String result, String peer) throws IOException {
        String line = System.currentTimeMillis() + "\t" + safe(action, 48) + "\t" +
            safe(target, 256) + "\t" + safe(result, 48) + "\t" + safe(peer, 64) + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.UTF_8);
        if (Files.size(file) + bytes.length > MAX_BYTES) {
            Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING);
            Files.createFile(file);
            privateFile(previous);
            privateFile(file);
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    synchronized List<Entry> recent(int limit) throws IOException {
        int wanted = Math.min(200, Math.max(0, limit));
        List<Entry> found = new ArrayList<>();
        for (Path path : List.of(file, previous)) {
            if (!Files.exists(path)) continue;
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && found.size() < wanted; i--) {
                String[] fields = lines.get(i).split("\t", -1);
                if (fields.length != 5) continue;
                try {
                    found.add(new Entry(Long.parseLong(fields[0]), fields[1], fields[2],
                        fields[3], fields[4]));
                } catch (NumberFormatException ignored) { }
            }
            if (found.size() >= wanted) break;
        }
        return List.copyOf(found);
    }

    private static String safe(String input, int limit) {
        if (input == null) return "—";
        String value = input.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static void privateFile(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) { }
    }
}
