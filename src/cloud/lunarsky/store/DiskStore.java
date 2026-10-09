package cloud.lunarsky.store;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import cloud.lunarsky.store.ObjectStorage.Metadata;
import cloud.lunarsky.store.ObjectStorage.OpenObject;
import cloud.lunarsky.store.ObjectStorage.ListedObject;
import cloud.lunarsky.store.ObjectStorage.ListPage;

final class DiskStore implements ObjectStorage {
    private static final long MAGIC_V1 = 0x4c534f424a303031L;
    private static final long MAGIC_V2 = 0x4c534f424a303032L;
    private static final int HEADER_V1 = 72;
    private static final int HEADER_V2 = 78;
    private final Path root, objects, temporary;
    private final FileChannel lockChannel;
    private final FileLock processLock;
    private final long maxObject, maxTotal;
    private final Object[] locks = new Object[128];
    private final NavigableMap<String, Metadata> index = new TreeMap<>();
    private long used;
    private long objectCount, legacyCount;

    record Record(Metadata metadata, int headerLength) {}

    DiskStore(Path root, long maxObject, long maxTotal) throws IOException {
        this.root = root;
        objects = root.resolve("objects"); temporary = root.resolve("pending");
        this.maxObject = maxObject; this.maxTotal = maxTotal;
        Arrays.setAll(locks, i -> new Object());
        Files.createDirectories(root);
        FileChannel channel = FileChannel.open(root.resolve(".process.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        boolean ready = false;
        try {
            try { acquired = channel.tryLock(); }
            catch (OverlappingFileLockException e) { throw new IOException("Data directory is already in use", e); }
            if (acquired == null) throw new IOException("Data directory is already in use");
            Files.createDirectories(objects); Files.createDirectories(temporary);
            syncDirectory(root);
            try (var paths = Files.list(temporary)) {
                for (Path p : paths.toList()) if (p.getFileName().toString().endsWith(".part")) Files.delete(p);
            }
            try (var paths = Files.walk(objects)) {
                for (Path p : paths.filter(Files::isRegularFile).toList()) {
                    Record record;
                    try (var in = new DataInputStream(Files.newInputStream(p))) { record = readRecord(in); }
                    Metadata meta = record.metadata();
                    if (Files.size(p) - record.headerLength() != meta.length())
                        throw new IOException("Truncated or oversized object record: " + p);
                    if (meta.key() != null) {
                        if (!p.equals(objectPath(meta.bucket(), meta.key())))
                            throw new IOException("Mismatched object record: " + p);
                        if (index.put(indexKey(meta.bucket(), meta.key()), meta) != null)
                            throw new IOException("Duplicate object record: " + p);
                    } else legacyCount++;
                    objectCount++;
                    used = Math.addExact(used, meta.length());
                }
            }
            ready = true;
        } finally {
            if (!ready) {
                if (acquired != null) acquired.release();
                channel.close();
            }
        }
        lockChannel = channel;
        processLock = acquired;
    }

    @Override public void close() throws IOException {
        processLock.release();
        lockChannel.close();
    }
    Path root() { return root; }
    long maxObject() { return maxObject; }
    long maxTotal() { return maxTotal; }
    synchronized long usedBytes() { return used; }
    synchronized int indexedObjects() { return index.size(); }
    synchronized long objectCount() { return objectCount; }
    synchronized long legacyObjects() { return legacyCount; }

    private static String indexKey(String bucket, String key) { return bucket + "\0" + key; }
    private Path objectPath(String bucket, String key) {
        String id = SigV4.hex(SigV4.hash((bucket + "/" + key).getBytes(StandardCharsets.UTF_8)));
        return objects.resolve(id.substring(0, 2)).resolve(id);
    }
    private synchronized Path object(String bucket, String key) throws IOException {
        Path path = objectPath(bucket, key);
        if (!Files.isDirectory(path.getParent())) {
            Files.createDirectories(path.getParent());
            syncDirectory(objects);
        }
        return path;
    }
    static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
    private Object lock(Path p) { return locks[(p.hashCode() & 0x7fffffff) % locks.length]; }

    public Metadata put(String bucket, String key, InputStream input, long length, String expectedHash,
                 String checksum, boolean createOnly, String contentType) throws IOException {
        if (length < 0) throw new StoreException(411, "MissingContentLength", "Content-Length is required");
        if (length > maxObject) throw new StoreException(413, "EntityTooLarge", "Object exceeds the configured size limit");
        byte[] bucketBytes = bucket.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] typeBytes = contentType.getBytes(StandardCharsets.UTF_8);
        if (bucketBytes.length > 63 || keyBytes.length > 1024 || typeBytes.length > 255)
            throw new StoreException(400, "InvalidArgument", "Object metadata is too long");
        int headerLength = HEADER_V2 + bucketBytes.length + keyBytes.length + typeBytes.length;
        Path destination = object(bucket, key), pending = Files.createTempFile(temporary, "upload-", ".part");
        try {
            MessageDigest sha = digest("SHA-256"), md5 = digest("MD5");
            long count = 0;
            try (OutputStream out = Files.newOutputStream(pending)) {
                out.write(new byte[headerLength]);
                byte[] buffer = new byte[65536]; int n;
                while ((n = input.read(buffer)) != -1) {
                    count += n;
                    if (count > length || count > maxObject)
                        throw new StoreException(413, "EntityTooLarge", "Payload exceeds declared size");
                    sha.update(buffer, 0, n); md5.update(buffer, 0, n); out.write(buffer, 0, n);
                }
            }
            if (count != length) throw new StoreException(400, "IncompleteBody", "Payload length does not match Content-Length");
            byte[] hash = sha.digest(), etag = md5.digest();
            if (!MessageDigest.isEqual(hash, HexFormat.of().parseHex(expectedHash)))
                throw new StoreException(400, "XAmzContentSHA256Mismatch", "Payload hash mismatch");
            if (checksum != null && !Base64.getEncoder().encodeToString(hash).equals(checksum))
                throw new StoreException(400, "BadDigest", "SHA-256 checksum mismatch");
            long modified = Instant.now().toEpochMilli();
            ByteBuffer header = ByteBuffer.allocate(headerLength).putLong(MAGIC_V2).putLong(count)
                .putLong(modified).put(etag).put(hash).putShort((short) bucketBytes.length)
                .putShort((short) keyBytes.length).putShort((short) typeBytes.length)
                .put(bucketBytes).put(keyBytes).put(typeBytes);
            header.flip();
            try (FileChannel file = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                while (header.hasRemaining()) file.write(header, header.position());
                file.force(true);
            }
            Metadata metadata = new Metadata(count, modified, SigV4.hex(etag), hash, bucket, key, contentType);
            synchronized (lock(destination)) {
                long previous = 0;
                boolean existed = Files.exists(destination);
                boolean legacy = false;
                if (existed) {
                    if (createOnly) throw new StoreException(412, "PreconditionFailed", "Object already exists");
                    try (var in = new DataInputStream(Files.newInputStream(destination))) {
                        Metadata old = readRecord(in).metadata();
                        previous = old.length();
                        legacy = old.key() == null;
                    }
                }
                synchronized (this) {
                    if (used - previous + count > maxTotal)
                        throw new StoreException(507, "InsufficientStorage", "Store capacity limit reached");
                    Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    used = used - previous + count;
                    if (!existed) objectCount++;
                    if (legacy) legacyCount--;
                    index.put(indexKey(bucket, key), metadata);
                    syncDirectory(destination.getParent());
                }
            }
            return metadata;
        } finally { Files.deleteIfExists(pending); }
    }

    public OpenObject open(String bucket, String key) throws IOException {
        Path destination = object(bucket, key);
        synchronized (lock(destination)) {
            final DataInputStream input;
            try { input = new DataInputStream(Files.newInputStream(destination)); }
            catch (NoSuchFileException e) { throw new StoreException(404, "NoSuchKey", "Object not found"); }
            try { return new OpenObject(readRecord(input).metadata(), input); }
            catch (IOException e) { input.close(); throw e; }
        }
    }

    public void delete(String bucket, String key) throws IOException {
        Path destination = object(bucket, key);
        synchronized (lock(destination)) {
            if (!Files.exists(destination)) return;
            long length;
            boolean legacy;
            try (var input = new DataInputStream(Files.newInputStream(destination))) {
                Metadata old = readRecord(input).metadata();
                length = old.length();
                legacy = old.key() == null;
            }
            synchronized (this) {
                Files.delete(destination);
                used -= length;
                objectCount--;
                if (legacy) legacyCount--;
                index.remove(indexKey(bucket, key));
                syncDirectory(destination.getParent());
            }
        }
    }

    public synchronized ListPage list(String bucket, String prefix, String delimiter, int maxKeys, String after) {
        List<ListedObject> entries = new ArrayList<>();
        List<String> prefixes = new ArrayList<>();
        if (maxKeys == 0) return new ListPage(entries, prefixes, null, false);
        String lastKey = null;
        boolean truncated = false;
        String activePrefix = null;
        for (Metadata meta : index.values()) {
            if (!meta.bucket().equals(bucket) || !meta.key().startsWith(prefix)) continue;
            String key = meta.key();
            if (after != null && key.compareTo(after) <= 0) continue;
            String group = null;
            if (!delimiter.isEmpty()) {
                int at = key.indexOf(delimiter, prefix.length());
                if (at >= 0) group = key.substring(0, at + delimiter.length());
            }
            if (group != null && group.equals(activePrefix)) { lastKey = key; continue; }
            if (entries.size() + prefixes.size() >= maxKeys) { truncated = true; break; }
            if (group != null) { prefixes.add(group); activePrefix = group; }
            else { entries.add(new ListedObject(key, meta)); activePrefix = null; }
            lastKey = key;
        }
        return new ListPage(entries, prefixes, truncated ? lastKey : null, truncated);
    }

    static Record readRecord(DataInputStream in) throws IOException {
        long magic = in.readLong();
        if (magic != MAGIC_V1 && magic != MAGIC_V2) throw new IOException("Invalid object record");
        long length = in.readLong(), modified = in.readLong();
        byte[] md5 = new byte[16], sha = new byte[32];
        in.readFully(md5); in.readFully(sha);
        if (length < 0) throw new IOException("Invalid object record length");
        if (magic == MAGIC_V1)
            return new Record(new Metadata(length, modified, SigV4.hex(md5), sha,
                null, null, "application/octet-stream"), HEADER_V1);
        int bucketLength = in.readUnsignedShort(), keyLength = in.readUnsignedShort(), typeLength = in.readUnsignedShort();
        if (bucketLength < 1 || bucketLength > 63 || keyLength < 1 || keyLength > 1024 || typeLength < 1 || typeLength > 255)
            throw new IOException("Invalid object record metadata");
        String bucket = utf8(in.readNBytes(bucketLength));
        String key = utf8(in.readNBytes(keyLength));
        String contentType = utf8(in.readNBytes(typeLength));
        if (bucket.getBytes(StandardCharsets.UTF_8).length != bucketLength ||
            key.getBytes(StandardCharsets.UTF_8).length != keyLength ||
            contentType.getBytes(StandardCharsets.UTF_8).length != typeLength)
            throw new IOException("Invalid object record metadata");
        return new Record(new Metadata(length, modified, SigV4.hex(md5), sha,
            bucket, key, contentType), HEADER_V2 + bucketLength + keyLength + typeLength);
    }
    private static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static MessageDigest digest(String algorithm) {
        try { return MessageDigest.getInstance(algorithm); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
