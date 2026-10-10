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
    private static final long MAGIC_V3 = 0x4c534f424a303033L;
    private static final long MAGIC_V4 = 0x4c534f424a303034L;
    private static final long MAGIC_V5 = 0x4c534f424a303035L;
    private static final int HEADER_V1 = 72;
    private static final int HEADER_V2 = 78;
    private static final int HEADER_V3 = 82;
    private static final int CHECKSUM_AREA = 512;
    private static final int HEADER_V4 = HEADER_V3 + 2 + CHECKSUM_AREA;
    private static final int ACL_AREA = 2048;
    private static final int HEADER_V5 = HEADER_V4 + 2;
    private static final int BUCKET_MAGIC = 0x4c534243;
    private static final int BUCKET_MAGIC_V2 = 0x4c534244;
    private static final int BUCKET_MAGIC_V3 = 0x4c534245;
    private static final int VERSION_MAGIC = 0x4c53564d;
    private final Path root, objects, temporary, catalog, versions;
    private final FileChannel lockChannel;
    private final FileLock processLock;
    private final long maxObject, maxTotal;
    private final Object[] locks = new Object[128];
    private final NavigableMap<String, Metadata> index = new TreeMap<>();
    private final NavigableMap<String, Bucket> buckets = new TreeMap<>();
    private final NavigableMap<String, List<VersionRecord>> histories = new TreeMap<>();
    private long used;
    private long objectCount, legacyCount;

    record Record(Metadata metadata, int headerLength) {}
    private record VersionRecord(String id, String storageId, boolean marker, long modified) {}

    DiskStore(Path root, long maxObject, long maxTotal) throws IOException {
        this.root = root;
        objects = root.resolve("objects");
        temporary = root.resolve("pending");
        catalog = root.resolve("buckets.bin");
        versions = root.resolve("versions");
        this.maxObject = maxObject;
        this.maxTotal = maxTotal;
        Arrays.setAll(locks, i -> new Object());
        Files.createDirectories(root);
        FileChannel channel = FileChannel.open(root.resolve(".process.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        boolean ready = false;
        try {
            try { acquired = channel.tryLock(); }
            catch (OverlappingFileLockException e) { throw new IOException("Data directory is already in use", e); }
            if (acquired == null) throw new IOException("Data directory is already in use");
            Files.createDirectories(objects);
            Files.createDirectories(temporary);
            Files.createDirectories(versions);
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
            if (Files.exists(catalog)) {
                try (DataInputStream input = new DataInputStream(Files.newInputStream(catalog))) {
                    int magic = input.readInt();
                    if (magic != BUCKET_MAGIC && magic != BUCKET_MAGIC_V2 && magic != BUCKET_MAGIC_V3)
                        throw new IOException("Invalid bucket catalog");
                    int count = input.readInt();
                    if (count < 0 || count > 1000) throw new IOException("Invalid bucket catalog");
                    for (int i = 0; i < count; i++) {
                        String name = input.readUTF();
                        long created = input.readLong();
                        VersioningState state = VersioningState.NEVER;
                        if (magic == BUCKET_MAGIC_V2 || magic == BUCKET_MAGIC_V3) {
                            int ordinal = input.readUnsignedByte();
                            if (ordinal >= VersioningState.values().length)
                                throw new IOException("Invalid bucket versioning state");
                            state = VersioningState.values()[ordinal];
                        }
                        Map<String, String> acl = Map.of();
                        if (magic == BUCKET_MAGIC_V3) {
                            int size = input.readUnsignedShort();
                            if (size > ACL_AREA) throw new IOException("Invalid bucket ACL");
                            acl = ObjectAttributes.decode(input.readNBytes(size));
                        }
                        if (!validBucket(name) || created < 0 ||
                            buckets.put(name, new Bucket(name, created, state, acl)) != null)
                            throw new IOException("Invalid bucket catalog");
                    }
                    if (input.read() != -1) throw new IOException("Invalid bucket catalog");
                }
            }
            loadHistories();
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
    @Override public Limits limits() { return new Limits(maxObject, maxTotal); }
    synchronized long usedBytes() { return used; }
    synchronized int indexedObjects() { return index.size(); }
    synchronized long objectCount() { return objectCount; }
    synchronized long legacyObjects() { return legacyCount; }

    private static boolean validBucket(String name) {
        return name.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");
    }

    @Override public synchronized void ensureBucket(String bucket) throws IOException {
        if (!buckets.containsKey(bucket)) createBucket(bucket);
    }

    @Override public synchronized Bucket bucket(String name) {
        Bucket found = buckets.get(name);
        if (found == null) throw new StoreException(404, "NoSuchBucket", "Bucket not found");
        return found;
    }

    @Override public synchronized List<Bucket> buckets() { return List.copyOf(buckets.values()); }

    @Override public synchronized void createBucket(String name) throws IOException {
        if (!validBucket(name)) throw new StoreException(400, "InvalidBucketName", "Invalid bucket name");
        if (buckets.containsKey(name))
            throw new StoreException(409, "BucketAlreadyOwnedByYou", "Bucket already exists");
        if (buckets.size() >= 1000) throw new StoreException(400, "TooManyBuckets", "Bucket limit reached");
        NavigableMap<String, Bucket> next = new TreeMap<>(buckets);
        next.put(name, new Bucket(name, Instant.now().toEpochMilli()));
        saveBuckets(next);
        buckets.clear();
        buckets.putAll(next);
    }

    @Override public synchronized void deleteBucket(String name) throws IOException {
        bucket(name);
        if (index.values().stream().anyMatch(meta -> name.equals(meta.bucket())))
            throw new StoreException(409, "BucketNotEmpty", "Bucket contains objects");
        if (histories.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith(name + "\0") &&
            !entry.getValue().isEmpty()))
            throw new StoreException(409, "BucketNotEmpty", "Bucket contains object versions");
        if (legacyCount > 0) {
            try (var paths = Files.walk(objects)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    try (var input = new DataInputStream(Files.newInputStream(path))) {
                        Metadata metadata = readRecord(input).metadata();
                        if (metadata.key() == null && name.equals(metadata.bucket()))
                            throw new StoreException(409, "BucketNotEmpty", "Bucket contains legacy objects");
                    }
                }
            }
        }
        NavigableMap<String, Bucket> next = new TreeMap<>(buckets);
        next.remove(name);
        saveBuckets(next);
        buckets.clear();
        buckets.putAll(next);
        for (String key : new ArrayList<>(histories.keySet())) {
            if (!key.startsWith(name + "\0") || !histories.get(key).isEmpty()) continue;
            String objectKey = key.substring(name.length() + 1);
            Path directory = historyDirectory(name, objectKey);
            Files.deleteIfExists(directory.resolve("manifest"));
            syncDirectory(directory);
            histories.remove(key);
        }
    }

    private void saveBuckets(NavigableMap<String, Bucket> next) throws IOException {
        Path pending = Files.createTempFile(temporary, "buckets-", ".part");
        try {
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(pending))) {
                output.writeInt(BUCKET_MAGIC_V3);
                output.writeInt(next.size());
                for (Bucket entry : next.values()) {
                    output.writeUTF(entry.name());
                    output.writeLong(entry.created());
                    output.writeByte(entry.versioning().ordinal());
                    byte[] acl = ObjectAttributes.encode(entry.acl(), ACL_AREA);
                    output.writeShort(acl.length);
                    output.write(acl);
                }
            }
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(pending, catalog, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(root);
        } finally { Files.deleteIfExists(pending); }
    }

    @Override public synchronized void setVersioning(String name, VersioningState state) throws IOException {
        if (state == VersioningState.NEVER)
            throw new StoreException(400, "InvalidArgument", "Versioning cannot be disabled after it is enabled");
        Bucket old = bucket(name);
        if (old.versioning() == VersioningState.NEVER && state == VersioningState.SUSPENDED)
            throw new StoreException(400, "InvalidArgument", "Enable versioning before suspending it");
        NavigableMap<String, Bucket> next = new TreeMap<>(buckets);
        next.put(name, new Bucket(name, old.created(), state, old.acl()));
        saveBuckets(next);
        buckets.clear();
        buckets.putAll(next);
    }

    @Override public synchronized void setBucketAcl(String name, Map<String, String> acl) throws IOException {
        Bucket old = bucket(name);
        NavigableMap<String, Bucket> next = new TreeMap<>(buckets);
        next.put(name, new Bucket(name, old.created(), old.versioning(), Map.copyOf(acl)));
        saveBuckets(next);
        buckets.clear();
        buckets.putAll(next);
    }

    private static String indexKey(String bucket, String key) { return bucket + "\0" + key; }
    private Path historyDirectory(String bucket, String key) {
        String id = SigV4.hex(SigV4.hash((bucket + "/" + key).getBytes(StandardCharsets.UTF_8)));
        return versions.resolve(id.substring(0, 2)).resolve(id);
    }
    private Path versionPath(String bucket, String key, String storageId) {
        return storageId.equals("legacy") ? objectPath(bucket, key) :
            historyDirectory(bucket, key).resolve(storageId);
    }
    private static Metadata withVersion(Metadata old, String id, String bucket, String key) {
        return new Metadata(old.length(), old.modified(), old.etag(), old.sha256(), bucket, key,
            old.contentType(), old.userMetadata(), old.tags(), id, old.checksums(), old.acl());
    }
    private List<VersionRecord> history(String bucket, String key) throws IOException {
        List<VersionRecord> found = histories.get(indexKey(bucket, key));
        if (found != null) return found;
        Metadata old = index.get(indexKey(bucket, key));
        if (old == null && Files.isRegularFile(objectPath(bucket, key))) {
            try (DataInputStream input = new DataInputStream(Files.newInputStream(objectPath(bucket, key)))) {
                old = readRecord(input).metadata();
            }
        }
        return old == null ? List.of() : List.of(new VersionRecord("null", "legacy", false, old.modified()));
    }
    private void saveHistory(String bucket, String key, List<VersionRecord> entries) throws IOException {
        Path directory = historyDirectory(bucket, key);
        if (!Files.isDirectory(directory)) {
            Files.createDirectories(directory);
            syncDirectory(directory.getParent());
        }
        Path pending = Files.createTempFile(temporary, "history-", ".part");
        try {
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(pending))) {
                output.writeInt(VERSION_MAGIC);
                output.writeUTF(bucket);
                output.writeUTF(key);
                output.writeInt(entries.size());
                for (VersionRecord entry : entries) {
                    output.writeUTF(entry.id());
                    output.writeUTF(entry.storageId());
                    output.writeBoolean(entry.marker());
                    output.writeLong(entry.modified());
                }
            }
            try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(pending, directory.resolve("manifest"), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
            syncDirectory(directory);
        } finally { Files.deleteIfExists(pending); }
        histories.put(indexKey(bucket, key), List.copyOf(entries));
    }
    private void loadHistories() throws IOException {
        Set<Path> referenced = new HashSet<>();
        try (var paths = Files.walk(versions)) {
            for (Path manifest : paths.filter(p -> p.getFileName().toString().equals("manifest")).toList()) {
                String bucket;
                String key;
                List<VersionRecord> entries = new ArrayList<>();
                try (DataInputStream input = new DataInputStream(Files.newInputStream(manifest))) {
                    if (input.readInt() != VERSION_MAGIC) throw new IOException("Invalid version manifest: " + manifest);
                    bucket = input.readUTF();
                    key = input.readUTF();
                    int count = input.readInt();
                    if (count < 0 || count > 1_000_000 || !manifest.getParent().equals(historyDirectory(bucket, key)))
                        throw new IOException("Invalid version manifest: " + manifest);
                    Set<String> ids = new HashSet<>();
                    for (int i = 0; i < count; i++) {
                        String id = input.readUTF();
                        String storageId = input.readUTF();
                        boolean marker = input.readBoolean();
                        long modified = input.readLong();
                        if ((!id.equals("null") && !id.matches("[0-9a-f-]{36}")) ||
                            (!marker && !storageId.equals("legacy") && !storageId.matches("[0-9a-f-]{36}")) ||
                            (marker && !storageId.isEmpty()) || !ids.add(id) || modified < 0)
                            throw new IOException("Invalid version manifest entry: " + manifest);
                        entries.add(new VersionRecord(id, storageId, marker, modified));
                        if (!marker) {
                            Path file = versionPath(bucket, key, storageId);
                            if (!Files.isRegularFile(file)) throw new IOException("Missing object version: " + file);
                            referenced.add(file);
                            if (!storageId.equals("legacy")) {
                                Record record;
                                try (DataInputStream data = new DataInputStream(Files.newInputStream(file))) {
                                    record = readRecord(data);
                                }
                                Metadata meta = record.metadata();
                                if (!bucket.equals(meta.bucket()) || !key.equals(meta.key()) ||
                                    Files.size(file) - record.headerLength() != meta.length())
                                    throw new IOException("Invalid object version: " + file);
                                objectCount++;
                                used = Math.addExact(used, meta.length());
                            }
                        }
                    }
                    if (input.read() != -1 || histories.put(indexKey(bucket, key), List.copyOf(entries)) != null)
                        throw new IOException("Invalid version manifest: " + manifest);
                }
                if (entries.isEmpty() || entries.getFirst().marker()) index.remove(indexKey(bucket, key));
                else {
                    VersionRecord current = entries.getFirst();
                    Path file = versionPath(bucket, key, current.storageId());
                    try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
                        index.put(indexKey(bucket, key),
                            withVersion(readRecord(input).metadata(), current.id(), bucket, key));
                    }
                }
            }
        }
        try (var paths = Files.walk(versions)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                if (!file.getFileName().toString().equals("manifest") && !referenced.contains(file)) {
                    Files.delete(file);
                    syncDirectory(file.getParent());
                }
            }
        }
        for (var entry : histories.entrySet()) {
            String[] parts = entry.getKey().split("\0", 2);
            if (entry.getValue().stream().anyMatch(version -> version.storageId().equals("legacy") && !version.marker()))
                continue;
            Path orphan = objectPath(parts[0], parts[1]);
            if (Files.isRegularFile(orphan)) {
                try (DataInputStream input = new DataInputStream(Files.newInputStream(orphan))) {
                    Metadata old = readRecord(input).metadata();
                    used -= old.length();
                    if (old.key() == null) legacyCount--;
                }
                Files.delete(orphan);
                objectCount--;
                syncDirectory(orphan.getParent());
            }
        }
    }
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
                 String checksum, boolean createOnly, String contentType,
                 Map<String, String> userMetadata, Map<String, String> tags,
                 java.util.function.Supplier<Map<String, String>> checksums,
                 Map<String, String> acl) throws IOException {
        if (length < 0) throw new StoreException(411, "MissingContentLength", "Content-Length is required");
        if (length > maxObject) throw new StoreException(413, "EntityTooLarge", "Object exceeds the configured size limit");
        byte[] bucketBytes = bucket.getBytes(StandardCharsets.UTF_8);
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        byte[] typeBytes = contentType.getBytes(StandardCharsets.UTF_8);
        byte[] metadataBytes = ObjectAttributes.encode(userMetadata, 4096);
        byte[] tagBytes = ObjectAttributes.encode(tags, 8192);
        validateMetadataLengths(bucketBytes, keyBytes, typeBytes);
        Path destination = object(bucket, key), pending = Files.createTempFile(temporary, "upload-", ".part");
        try {
            Metadata metadata = stagePut(pending, input, length, expectedHash, checksum,
                bucket, key, contentType, bucketBytes, keyBytes, typeBytes,
                metadataBytes, tagBytes, userMetadata, tags, checksums, acl);
            return installPending(destination, pending, metadata, createOnly);
        } finally { Files.deleteIfExists(pending); }
    }

    private static void validateMetadataLengths(byte[] bucketBytes, byte[] keyBytes, byte[] typeBytes) {
        if (bucketBytes.length > 63 || keyBytes.length > 1024 || typeBytes.length > 255)
            throw new StoreException(400, "InvalidArgument", "Object metadata is too long");
    }

    private Metadata stagePut(Path pending, InputStream input, long length, String expectedHash, String checksum,
                              String bucket, String key, String contentType,
                              byte[] bucketBytes, byte[] keyBytes, byte[] typeBytes,
                              byte[] metadataBytes, byte[] tagBytes,
                              Map<String, String> userMetadata, Map<String, String> tags,
                              java.util.function.Supplier<Map<String, String>> checksums,
                              Map<String, String> acl) throws IOException {
        byte[] aclBytes = ObjectAttributes.encode(acl, ACL_AREA);
        int headerLength = HEADER_V5 + aclBytes.length +
            bucketBytes.length + keyBytes.length + typeBytes.length +
            metadataBytes.length + tagBytes.length;
        MessageDigest sha = digest("SHA-256"), md5 = digest("MD5");
        Crc64Nvme crc64 = new Crc64Nvme();
        long count = 0;
        try (OutputStream out = Files.newOutputStream(pending)) {
            out.write(new byte[headerLength]);
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) != -1) {
                count += n;
                if (count > length || count > maxObject)
                    throw new StoreException(413, "EntityTooLarge", "Payload exceeds declared size");
                sha.update(buffer, 0, n);
                md5.update(buffer, 0, n);
                crc64.update(buffer, 0, n);
                out.write(buffer, 0, n);
            }
        }
        if (count != length) throw new StoreException(400, "IncompleteBody", "Payload length does not match Content-Length");
        byte[] hash = sha.digest(), etag = md5.digest();
        if (expectedHash != null && !MessageDigest.isEqual(hash, HexFormat.of().parseHex(expectedHash)))
            throw new StoreException(400, "XAmzContentSHA256Mismatch", "Payload hash mismatch");
        if (checksum != null && !Base64.getEncoder().encodeToString(hash).equals(checksum))
            throw new StoreException(400, "BadDigest", "SHA-256 checksum mismatch");
        Map<String, String> suppliedChecksums = checksums.get();
        Map<String, String> storedChecksums = suppliedChecksums.isEmpty() ?
            Map.of("x-amz-checksum-crc64nvme", crc64.encoded()) : Map.copyOf(suppliedChecksums);
        byte[] checksumBytes = ObjectAttributes.encode(storedChecksums, CHECKSUM_AREA);
        long modified = Instant.now().toEpochMilli();
        ByteBuffer header = ByteBuffer.allocate(headerLength).putLong(MAGIC_V5).putLong(count)
            .putLong(modified).put(etag).put(hash).putShort((short) bucketBytes.length)
            .putShort((short) keyBytes.length).putShort((short) typeBytes.length)
            .putShort((short) metadataBytes.length).putShort((short) tagBytes.length)
            .putShort((short) checksumBytes.length);
        header.position(header.position() + CHECKSUM_AREA - checksumBytes.length);
        header.put(checksumBytes).putShort((short) aclBytes.length);
        header.put(aclBytes).put(bucketBytes).put(keyBytes).put(typeBytes).put(metadataBytes).put(tagBytes);
        header.flip();
        try (FileChannel file = FileChannel.open(pending, StandardOpenOption.WRITE)) {
            while (header.hasRemaining()) file.write(header, header.position());
            file.force(true);
        }
        return new Metadata(count, modified, SigV4.hex(etag), hash, bucket, key, contentType,
            Map.copyOf(userMetadata), Map.copyOf(tags), null, storedChecksums, Map.copyOf(acl));
    }

    private Metadata installPending(Path destination, Path pending, Metadata metadata,
                                    boolean createOnly) throws IOException {
        synchronized (lock(destination)) {
            synchronized (this) {
                Bucket configured = buckets.get(metadata.bucket());
                if (configured != null && configured.versioning() != VersioningState.NEVER)
                    return installVersionedPending(destination, pending, metadata, createOnly,
                        configured.versioning());
            }
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
                if (Files.exists(catalog) && !buckets.containsKey(metadata.bucket()))
                    throw new StoreException(404, "NoSuchBucket", "Bucket not found");
                if (used - previous + metadata.length() > maxTotal)
                    throw new StoreException(507, "InsufficientStorage", "Store capacity limit reached");
                Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                used = used - previous + metadata.length();
                if (!existed) objectCount++;
                if (legacy) legacyCount--;
                index.put(indexKey(metadata.bucket(), metadata.key()), metadata);
                syncDirectory(destination.getParent());
            }
            return metadata;
        }
    }

    private Metadata installVersionedPending(Path destination, Path pending, Metadata metadata,
                                             boolean createOnly, VersioningState state) throws IOException {
        String bucket = metadata.bucket();
        String key = metadata.key();
        if (createOnly && index.containsKey(indexKey(bucket, key)))
            throw new StoreException(412, "PreconditionFailed", "Object already exists");
        List<VersionRecord> old = history(bucket, key);
        String id = state == VersioningState.ENABLED ? UUID.randomUUID().toString() : "null";
        String storageId = UUID.randomUUID().toString();
        VersionRecord discarded = null;
        long replaced = 0;
        if (id.equals("null")) {
            for (VersionRecord entry : old) {
                if (!entry.id().equals("null") || entry.marker()) continue;
                discarded = entry;
                try (DataInputStream input = new DataInputStream(Files.newInputStream(
                    versionPath(bucket, key, entry.storageId())))) {
                    replaced = readRecord(input).metadata().length();
                }
            }
        }
        if (maxTotal - (used - replaced) < metadata.length())
            throw new StoreException(507, "InsufficientStorage", "Store capacity limit reached");
        Path target = versionPath(bucket, key, storageId);
        Files.createDirectories(target.getParent());
        syncDirectory(target.getParent().getParent());
        Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        syncDirectory(target.getParent());
        List<VersionRecord> next = new ArrayList<>();
        next.add(new VersionRecord(id, storageId, false, metadata.modified()));
        for (VersionRecord entry : old) if (!entry.id().equals(id)) next.add(entry);
        saveHistory(bucket, key, next);
        used += metadata.length();
        objectCount++;
        Metadata current = withVersion(metadata, id, bucket, key);
        index.put(indexKey(bucket, key), current);
        if (discarded != null) removeStoredVersion(bucket, key, discarded, replaced);
        return current;
    }

    private void removeStoredVersion(String bucket, String key, VersionRecord entry, long length) throws IOException {
        Path file = versionPath(bucket, key, entry.storageId());
        if (entry.storageId().equals("legacy")) {
            try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
                if (readRecord(input).metadata().key() == null) legacyCount--;
            }
        }
        Files.deleteIfExists(file);
        syncDirectory(file.getParent());
        used -= length;
        objectCount--;
    }

    public OpenObject open(String bucket, String key) throws IOException {
        return open(bucket, key, null);
    }

    @Override public OpenObject open(String bucket, String key, String versionId) throws IOException {
        Path destination = object(bucket, key);
        synchronized (lock(destination)) {
            VersionRecord selected;
            synchronized (this) {
                List<VersionRecord> entries = history(bucket, key);
                selected = versionId == null ? (entries.isEmpty() ? null : entries.getFirst()) :
                    entries.stream().filter(entry -> entry.id().equals(versionId)).findFirst().orElse(null);
            }
            if (selected == null)
                throw new StoreException(404, versionId == null ? "NoSuchKey" : "NoSuchVersion",
                    "Object version not found");
            if (selected.marker())
                throw StoreException.deletedVersion(selected.id(), selected.modified(), versionId != null);
            final DataInputStream input;
            try { input = new DataInputStream(Files.newInputStream(
                versionPath(bucket, key, selected.storageId()))); }
            catch (NoSuchFileException e) { throw new StoreException(404, "NoSuchKey", "Object not found"); }
            try {
                Bucket configured = buckets.get(bucket);
                String exposedId = histories.containsKey(indexKey(bucket, key)) ||
                    configured != null && configured.versioning() != VersioningState.NEVER ? selected.id() : null;
                return new OpenObject(withVersion(readRecord(input).metadata(), exposedId, bucket, key), input);
            }
            catch (IOException e) {
                input.close();
                throw e;
            }
        }
    }

    public void delete(String bucket, String key) throws IOException {
        delete(bucket, key, null);
    }

    @Override public DeleteResult delete(String bucket, String key, String versionId) throws IOException {
        Path destination = object(bucket, key);
        synchronized (lock(destination)) {
            synchronized (this) {
                Bucket configured = buckets.get(bucket);
                VersioningState state = configured == null ? VersioningState.NEVER : configured.versioning();
                if (state != VersioningState.NEVER || versionId != null)
                    return deleteVersioned(bucket, key, versionId, state);
            }
            if (!Files.exists(destination)) return new DeleteResult(null, false);
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
            return new DeleteResult(null, false);
        }
    }

    private DeleteResult deleteVersioned(String bucket, String key, String versionId,
                                         VersioningState state) throws IOException {
        List<VersionRecord> old = history(bucket, key);
        List<VersionRecord> next = new ArrayList<>();
        VersionRecord removed = null;
        if (versionId != null) {
            for (VersionRecord entry : old) {
                if (entry.id().equals(versionId)) removed = entry;
                else next.add(entry);
            }
            if (removed == null)
                throw new StoreException(404, "NoSuchVersion", "Object version not found");
        } else {
            String id = state == VersioningState.ENABLED ? UUID.randomUUID().toString() : "null";
            next.add(new VersionRecord(id, "", true, Instant.now().toEpochMilli()));
            for (VersionRecord entry : old) {
                if (state == VersioningState.SUSPENDED && entry.id().equals("null")) removed = entry;
                else next.add(entry);
            }
        }
        saveHistory(bucket, key, next);
        updateCurrentIndex(bucket, key, next);
        if (removed != null && !removed.marker()) {
            Path file = versionPath(bucket, key, removed.storageId());
            long length;
            try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
                length = readRecord(input).metadata().length();
            }
            removeStoredVersion(bucket, key, removed, length);
        }
        if (versionId != null) return new DeleteResult(versionId, removed.marker());
        return new DeleteResult(next.getFirst().id(), true);
    }

    private void updateCurrentIndex(String bucket, String key, List<VersionRecord> entries) throws IOException {
        if (entries.isEmpty() || entries.getFirst().marker()) {
            index.remove(indexKey(bucket, key));
            return;
        }
        VersionRecord latest = entries.getFirst();
        try (DataInputStream input = new DataInputStream(Files.newInputStream(
            versionPath(bucket, key, latest.storageId())))) {
            index.put(indexKey(bucket, key),
                withVersion(readRecord(input).metadata(), latest.id(), bucket, key));
        }
    }

    @Override public Map<String, String> tags(String bucket, String key) throws IOException {
        return tags(bucket, key, null);
    }

    @Override public Map<String, String> tags(String bucket, String key, String versionId) throws IOException {
        try (OpenObject object = open(bucket, key, versionId)) { return object.metadata().tags(); }
    }

    @Override public void setTags(String bucket, String key, Map<String, String> tags) throws IOException {
        setTags(bucket, key, null, tags);
    }

    @Override public void setTags(String bucket, String key, String versionId,
                                  Map<String, String> tags) throws IOException {
        rewriteAttributes(bucket, key, versionId, tags, null);
    }

    @Override public void setObjectAcl(String bucket, String key, String versionId,
                                       Map<String, String> acl) throws IOException {
        rewriteAttributes(bucket, key, versionId, null, acl);
    }

    private void rewriteAttributes(String bucket, String key, String versionId,
                                   Map<String, String> tags, Map<String, String> acl) throws IOException {
        Path destination = object(bucket, key);
        synchronized (lock(destination)) {
            VersionRecord selected;
            boolean current;
            synchronized (this) {
                List<VersionRecord> entries = history(bucket, key);
                selected = versionId == null ? (entries.isEmpty() ? null : entries.getFirst()) :
                    entries.stream().filter(entry -> entry.id().equals(versionId)).findFirst().orElse(null);
                current = selected != null && !entries.isEmpty() && selected == entries.getFirst();
            }
            if (selected == null || selected.marker())
                throw new StoreException(404, versionId == null ? "NoSuchKey" : "NoSuchVersion",
                    "Object version not found");
            destination = versionPath(bucket, key, selected.storageId());
            Path pending = Files.createTempFile(temporary, "tags-", ".part");
            try {
                try (DataInputStream input = new DataInputStream(Files.newInputStream(destination));
                     OutputStream output = Files.newOutputStream(pending)) {
                    Metadata old = readRecord(input).metadata();
                    if (old.key() == null) throw new StoreException(501, "NotImplemented", "Legacy object tags are unsupported");
                    Metadata updated = new Metadata(old.length(), old.modified(), old.etag(), old.sha256(),
                        bucket, key, old.contentType(), old.userMetadata(),
                        tags == null ? old.tags() : Map.copyOf(tags),
                        old.versionId(), old.checksums(),
                        acl == null ? old.acl() : Map.copyOf(acl));
                    output.write(recordHeader(updated));
                    if (input.transferTo(output) != old.length()) throw new IOException("Object length changed during tag update");
                }
                try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.WRITE)) { channel.force(true); }
                synchronized (this) {
                    Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    if (current) {
                        Metadata old = index.get(indexKey(bucket, key));
                        index.put(indexKey(bucket, key), new Metadata(old.length(), old.modified(), old.etag(),
                            old.sha256(), bucket, key, old.contentType(), old.userMetadata(),
                            tags == null ? old.tags() : Map.copyOf(tags),
                            old.versionId(), old.checksums(),
                            acl == null ? old.acl() : Map.copyOf(acl)));
                    }
                    syncDirectory(destination.getParent());
                }
            } finally { Files.deleteIfExists(pending); }
        }
    }

    private static byte[] recordHeader(Metadata metadata) {
        byte[] bucket = metadata.bucket().getBytes(StandardCharsets.UTF_8);
        byte[] key = metadata.key().getBytes(StandardCharsets.UTF_8);
        byte[] type = metadata.contentType().getBytes(StandardCharsets.UTF_8);
        byte[] custom = ObjectAttributes.encode(metadata.userMetadata(), 4096);
        byte[] tags = ObjectAttributes.encode(metadata.tags(), 8192);
        byte[] checksums = ObjectAttributes.encode(metadata.checksums(), CHECKSUM_AREA);
        byte[] acl = ObjectAttributes.encode(metadata.acl(), ACL_AREA);
        ByteBuffer header = ByteBuffer.allocate(HEADER_V5 + acl.length +
            bucket.length + key.length + type.length + custom.length + tags.length)
            .putLong(MAGIC_V5).putLong(metadata.length()).putLong(metadata.modified())
            .put(HexFormat.of().parseHex(metadata.etag())).put(metadata.sha256())
            .putShort((short) bucket.length).putShort((short) key.length).putShort((short) type.length)
            .putShort((short) custom.length).putShort((short) tags.length)
            .putShort((short) checksums.length);
        header.position(header.position() + CHECKSUM_AREA - checksums.length);
        header.put(checksums).putShort((short) acl.length);
        return header.put(acl).put(bucket).put(key).put(type).put(custom).put(tags).array();
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
            if (group != null && group.equals(activePrefix)) {
                lastKey = key;
                continue;
            }
            if (entries.size() + prefixes.size() >= maxKeys) {
                truncated = true;
                break;
            }
            if (group != null) {
                prefixes.add(group);
                activePrefix = group;
            } else {
                entries.add(new ListedObject(key, meta));
                activePrefix = null;
            }
            lastKey = key;
        }
        return new ListPage(entries, prefixes, truncated ? lastKey : null, truncated);
    }

    @Override public synchronized VersionPage listVersions(String bucket, String prefix,
                                                             String keyMarker, String versionMarker,
                                                             int maxKeys) throws IOException {
        bucket(bucket);
        if (versionMarker != null && keyMarker == null)
            throw new StoreException(400, "InvalidArgument", "Version marker requires a key marker");
        if (maxKeys == 0) return new VersionPage(List.of(), null, null, false);
        NavigableSet<String> keys = new TreeSet<>();
        for (String name : histories.keySet()) if (name.startsWith(bucket + "\0")) keys.add(name.substring(bucket.length() + 1));
        for (Metadata meta : index.values()) if (bucket.equals(meta.bucket())) keys.add(meta.key());
        List<VersionEntry> page = new ArrayList<>();
        String nextKey = null;
        String nextVersion = null;
        boolean truncated = false;
        for (String key : keys) {
            if (!key.startsWith(prefix) || keyMarker != null && key.compareTo(keyMarker) < 0) continue;
            List<VersionRecord> entries = history(bucket, key);
            boolean pastMarker = keyMarker == null || !key.equals(keyMarker) || versionMarker == null;
            for (int i = 0; i < entries.size(); i++) {
                VersionRecord entry = entries.get(i);
                if (keyMarker != null && key.equals(keyMarker)) {
                    if (versionMarker == null) continue;
                    if (!pastMarker) {
                        if (entry.id().equals(versionMarker)) pastMarker = true;
                        continue;
                    }
                }
                if (page.size() == maxKeys) {
                    truncated = true;
                    break;
                }
                Metadata meta = null;
                if (!entry.marker()) {
                    try (DataInputStream input = new DataInputStream(Files.newInputStream(
                        versionPath(bucket, key, entry.storageId())))) {
                        meta = withVersion(readRecord(input).metadata(), entry.id(), bucket, key);
                    }
                }
                page.add(new VersionEntry(key, entry.id(), entry.modified(), entry.marker(), i == 0, meta));
                nextKey = key;
                nextVersion = entry.id();
            }
            if (truncated) break;
        }
        return new VersionPage(page, truncated ? nextKey : null, truncated ? nextVersion : null, truncated);
    }

    static Record readRecord(DataInputStream in) throws IOException {
        long magic = in.readLong();
        if (magic != MAGIC_V1 && magic != MAGIC_V2 && magic != MAGIC_V3 && magic != MAGIC_V4 &&
            magic != MAGIC_V5)
            throw new IOException("Invalid object record");
        long length = in.readLong(), modified = in.readLong();
        byte[] md5 = new byte[16], sha = new byte[32];
        in.readFully(md5);
        in.readFully(sha);
        if (length < 0) throw new IOException("Invalid object record length");
        if (magic == MAGIC_V1)
            return new Record(new Metadata(length, modified, SigV4.hex(md5), sha,
                null, null, "application/octet-stream"), HEADER_V1);
        int bucketLength = in.readUnsignedShort(), keyLength = in.readUnsignedShort(), typeLength = in.readUnsignedShort();
        int metadataLength = magic == MAGIC_V3 || magic == MAGIC_V4 || magic == MAGIC_V5 ? in.readUnsignedShort() : 0;
        int tagsLength = magic == MAGIC_V3 || magic == MAGIC_V4 || magic == MAGIC_V5 ? in.readUnsignedShort() : 0;
        int checksumLength = magic == MAGIC_V4 || magic == MAGIC_V5 ? in.readUnsignedShort() : 0;
        if (bucketLength < 1 || bucketLength > 63 || keyLength < 1 || keyLength > 1024 || typeLength < 1 || typeLength > 255)
            throw new IOException("Invalid object record metadata");
        if (metadataLength > 4096 || tagsLength > 8192 || checksumLength > CHECKSUM_AREA)
            throw new IOException("Invalid object attributes");
        Map<String, String> checksums = Map.of();
        if (magic == MAGIC_V4 || magic == MAGIC_V5) {
            byte[] area = in.readNBytes(CHECKSUM_AREA);
            if (area.length != CHECKSUM_AREA) throw new IOException("Truncated checksum attributes");
            checksums = ObjectAttributes.decode(Arrays.copyOfRange(area,
                CHECKSUM_AREA - checksumLength, CHECKSUM_AREA));
        }
        Map<String, String> acl = Map.of();
        int aclLength = 0;
        if (magic == MAGIC_V5) {
            int size = in.readUnsignedShort();
            if (size > ACL_AREA) throw new IOException("Invalid object ACL");
            byte[] bytes = in.readNBytes(size);
            if (bytes.length != size) throw new IOException("Truncated object ACL");
            acl = ObjectAttributes.decode(bytes);
            aclLength = size;
        }
        String bucket = utf8(in.readNBytes(bucketLength));
        String key = utf8(in.readNBytes(keyLength));
        String contentType = utf8(in.readNBytes(typeLength));
        if (bucket.getBytes(StandardCharsets.UTF_8).length != bucketLength ||
            key.getBytes(StandardCharsets.UTF_8).length != keyLength ||
            contentType.getBytes(StandardCharsets.UTF_8).length != typeLength)
            throw new IOException("Invalid object record metadata");
        Map<String, String> metadata = ObjectAttributes.decode(in.readNBytes(metadataLength));
        Map<String, String> tags = ObjectAttributes.decode(in.readNBytes(tagsLength));
        return new Record(new Metadata(length, modified, SigV4.hex(md5), sha,
            bucket, key, contentType, metadata, tags, null, checksums, acl),
            (magic == MAGIC_V5 ? HEADER_V5 + aclLength :
             magic == MAGIC_V4 ? HEADER_V4 : magic == MAGIC_V3 ? HEADER_V3 : HEADER_V2) +
                bucketLength + keyLength + typeLength + metadataLength + tagsLength);
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
