package cloud.lunarsky.store;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class BucketNameCatalog {
    private final DiskStore store;
    private final Path catalog;
    private final Path deletion;

    BucketNameCatalog(DiskStore store) {
        this.store = store;
        catalog = store.root().resolve("bucket-names.txt");
        deletion = store.root().resolve("bucket-name-deletion.txt");
    }

    Map<String, String> load() throws IOException {
        Map<String, String> loaded = new HashMap<>();
        if (Files.exists(catalog)) {
            for (String line : Files.readAllLines(catalog, StandardCharsets.UTF_8)) {
                String[] names = parse(line);
                if (loaded.putIfAbsent(names[0], names[1]) != null)
                    throw new IOException("Duplicate bucket alias");
            }
        }
        Set<String> physical = store.buckets().stream().map(ObjectStorage.Bucket::name)
            .collect(Collectors.toSet());
        if (Files.exists(deletion)) {
            String[] names = parse(Files.readString(deletion, StandardCharsets.UTF_8).strip());
            String target = loaded.get(names[0]);
            if (target != null && !target.equals(names[1]))
                throw new IOException("Bucket deletion conflicts with name catalog");
            if (!physical.contains(names[1])) {
                loaded.remove(names[0]);
                save(loaded);
            }
            finishDelete();
        }
        if (!physical.containsAll(loaded.values()) ||
            loaded.values().stream().distinct().count() != loaded.size() ||
            loaded.keySet().stream().anyMatch(physical::contains))
            throw new IOException("Bucket name catalog conflicts with stored buckets");
        return Map.copyOf(loaded);
    }

    private static String[] parse(String line) throws IOException {
        String[] names = line.split("\t", -1);
        if (names.length != 2 || !valid(names[0]) || !valid(names[1]) || names[0].equals(names[1]))
            throw new IOException("Invalid bucket name catalog");
        return names;
    }

    private static boolean valid(String name) {
        return name.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");
    }

    void save(Map<String, String> next) throws IOException {
        StringBuilder text = new StringBuilder();
        next.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            text.append(entry.getKey()).append('\t').append(entry.getValue()).append('\n'));
        write(catalog, text.toString());
    }

    void beginDelete(String visible, String physical) throws IOException {
        if (Files.exists(deletion))
            throw new IOException("An interrupted bucket deletion requires a restart before deleting another renamed bucket");
        write(deletion, visible + "\t" + physical + "\n");
    }

    void finishDelete() throws IOException {
        Files.deleteIfExists(deletion);
        DiskStore.syncDirectory(store.root());
    }

    private void write(Path destination, String text) throws IOException {
        Path pending = Files.createTempFile(store.root().resolve("pending"), "bucket-names-", ".part");
        try {
            Files.writeString(pending, text, StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            DiskStore.syncDirectory(store.root());
        } finally {
            Files.deleteIfExists(pending);
        }
    }
}
