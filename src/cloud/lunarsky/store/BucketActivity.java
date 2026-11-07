package cloud.lunarsky.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

final class BucketActivity implements AutoCloseable {
    private static final int DAYS = 365;
    private final Path file;
    private final ConcurrentHashMap<Key, Counts> counts = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "objectstore-bucket-activity");
        thread.setDaemon(true);
        return thread;
    });

    record Day(LocalDate date, long reads, long writes) {
        long total() { return reads + writes; }
    }

    private record Key(String bucket, long epochDay) { }

    private static final class Counts {
        final LongAdder reads = new LongAdder();
        final LongAdder writes = new LongAdder();
    }

    BucketActivity(Path file) {
        this.file = file.toAbsolutePath().normalize();
        try { load(); }
        catch (IOException | IllegalArgumentException error) {
            counts.clear();
            System.err.println("Could not load bucket activity: " + error.getMessage());
        }
        writer.scheduleWithFixedDelay(this::flushSafely, 60, 60, TimeUnit.SECONDS);
    }

    void record(String bucket, String method) {
        if (!validBucket(bucket)) return;
        if (!method.equals("GET") && !method.equals("HEAD") &&
            !method.equals("PUT") && !method.equals("POST") && !method.equals("DELETE")) return;
        long epochDay = Math.floorDiv(System.currentTimeMillis(), 86_400_000L);
        Counts day = counts.computeIfAbsent(new Key(bucket, epochDay),
            ignored -> new Counts());
        if (method.equals("GET") || method.equals("HEAD")) day.reads.increment();
        else day.writes.increment();
        dirty.set(true);
    }

    List<Day> days(String bucket) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = today.minusDays(DAYS - 1);
        List<Day> result = new ArrayList<>(DAYS);
        for (int offset = 0; offset < DAYS; offset++) {
            LocalDate date = start.plusDays(offset);
            Counts value = counts.get(new Key(bucket, date.toEpochDay()));
            result.add(new Day(date, value == null ? 0 : value.reads.sum(),
                value == null ? 0 : value.writes.sum()));
        }
        return result;
    }

    synchronized void rename(String oldName, String newName) {
        if (oldName.equals(newName)) return;
        for (var entry : counts.entrySet()) {
            Key key = entry.getKey();
            if (!key.bucket.equals(oldName)) continue;
            Counts moved = counts.computeIfAbsent(new Key(newName, key.epochDay), ignored -> new Counts());
            moved.reads.add(entry.getValue().reads.sum());
            moved.writes.add(entry.getValue().writes.sum());
            counts.remove(key, entry.getValue());
        }
        dirty.set(true);
    }

    synchronized void remove(String bucket) {
        counts.keySet().removeIf(key -> key.bucket.equals(bucket));
        dirty.set(true);
    }

    private void load() throws IOException {
        if (!Files.exists(file)) return;
        if (Files.size(file) > 64L * 1024 * 1024)
            throw new IOException("Activity file is too large");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().equals("ObjectStoreActivity 1"))
            throw new IOException("Invalid activity file header");
        LocalDate earliest = LocalDate.now(ZoneOffset.UTC).minusDays(DAYS - 1);
        for (int index = 1; index < lines.size(); index++) {
            String[] fields = lines.get(index).split("\\t", -1);
            if (fields.length != 4 || !validBucket(fields[0]))
                throw new IOException("Invalid activity record");
            try {
                LocalDate date = LocalDate.parse(fields[1]);
                long reads = Long.parseLong(fields[2]);
                long writes = Long.parseLong(fields[3]);
                if (reads < 0 || writes < 0) throw new NumberFormatException();
                if (date.isBefore(earliest) || date.isAfter(LocalDate.now(ZoneOffset.UTC))) continue;
                Counts value = counts.computeIfAbsent(new Key(fields[0], date.toEpochDay()),
                    ignored -> new Counts());
                value.reads.add(reads);
                value.writes.add(writes);
            } catch (RuntimeException error) {
                throw new IOException("Invalid activity record", error);
            }
        }
    }

    private synchronized void flushSafely() {
        if (!dirty.getAndSet(false)) return;
        try { flush(); }
        catch (IOException error) {
            dirty.set(true);
            System.err.println("Could not save bucket activity: " + error.getMessage());
        }
    }

    private void flush() throws IOException {
        LocalDate earliest = LocalDate.now(ZoneOffset.UTC).minusDays(DAYS - 1);
        counts.keySet().removeIf(key -> key.epochDay < earliest.toEpochDay());
        StringBuilder data = new StringBuilder("ObjectStoreActivity 1\n");
        counts.entrySet().stream().sorted((left, right) -> {
            int byBucket = left.getKey().bucket.compareTo(right.getKey().bucket);
            return byBucket == 0 ? Long.compare(left.getKey().epochDay, right.getKey().epochDay) : byBucket;
        }).forEach(entry -> data.append(entry.getKey().bucket).append('\t')
            .append(LocalDate.ofEpochDay(entry.getKey().epochDay)).append('\t')
            .append(entry.getValue().reads.sum())
            .append('\t').append(entry.getValue().writes.sum()).append('\n'));
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "bucket-activity-", ".part");
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(data.toString());
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static boolean validBucket(String bucket) {
        if (bucket == null || bucket.length() < 3 || bucket.length() > 63 ||
            bucket.charAt(0) == '-' || bucket.charAt(bucket.length() - 1) == '-') return false;
        for (int index = 0; index < bucket.length(); index++) {
            char character = bucket.charAt(index);
            if (character != '-' && (character < 'a' || character > 'z') &&
                (character < '0' || character > '9')) return false;
        }
        return true;
    }

    @Override public void close() {
        writer.shutdown();
        flushSafely();
    }
}
