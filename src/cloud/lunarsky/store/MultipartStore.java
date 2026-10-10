package cloud.lunarsky.store;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import cloud.lunarsky.store.MultipartStorage.Part;

final class MultipartStore implements MultipartStorage {
    private static final int MAGIC = 0x4c534d50;
    private static final int MAGIC_V2 = 0x4c534d51;
    private static final int MAGIC_V3 = 0x4c534d52;
    private final DiskStore store;
    private final Path root;
    private long staged;
    private int active;

    private record Upload(String bucket, String key, String contentType,
                          Map<String, String> userMetadata, Map<String, String> tags,
                          Map<String, String> acl) {}

    MultipartStore(DiskStore store) throws IOException {
        this.store = store;
        root = store.root().resolve("multipart");
        Files.createDirectories(root);
        DiskStore.syncDirectory(store.root());
        try (var uploads = Files.list(root)) {
            for (Path dir : uploads.toList()) {
                if (dir.getFileName().toString().startsWith(".creating-")) {
                    discardCreating(dir);
                    continue;
                }
                if (!Files.isDirectory(dir)) throw new IOException("Invalid multipart upload entry: " + dir);
                readUpload(dir);
                active++;
                try (var files = Files.list(dir)) {
                    for (Path file : files.toList()) {
                        if (file.getFileName().toString().matches("part-[0-9]{5}"))
                            staged = Math.addExact(staged, Files.size(file));
                        else if (!file.getFileName().toString().equals("manifest"))
                            throw new IOException("Invalid multipart upload entry: " + file);
                    }
                }
            }
        }
        if (staged > store.maxTotal()) throw new IOException("Multipart staging limit exceeded");
    }

    public synchronized String create(String bucket, String key, String contentType,
                                      Map<String, String> userMetadata, Map<String, String> tags,
                                      Map<String, String> acl) throws IOException {
        if (Files.exists(store.root().resolve("buckets.bin"))) store.bucket(bucket);
        if (active >= 32) throw new StoreException(503, "SlowDown", "Too many active uploads");
        String id = UUID.randomUUID().toString();
        Path pending = root.resolve(".creating-" + id), dir = root.resolve(id);
        Files.createDirectory(pending);
        try {
            try (var output = new DataOutputStream(Files.newOutputStream(pending.resolve("manifest"), StandardOpenOption.CREATE_NEW))) {
                output.writeInt(MAGIC_V3);
                output.writeUTF(bucket);
                output.writeUTF(key);
                output.writeUTF(contentType);
                byte[] custom = ObjectAttributes.encode(userMetadata, 4096);
                byte[] encodedTags = ObjectAttributes.encode(tags, 8192);
                output.writeShort(custom.length);
                output.write(custom);
                output.writeShort(encodedTags.length);
                output.write(encodedTags);
                byte[] encodedAcl = ObjectAttributes.encode(acl, 2048);
                output.writeShort(encodedAcl.length);
                output.write(encodedAcl);
            }
            try (var channel = java.nio.channels.FileChannel.open(pending.resolve("manifest"), StandardOpenOption.READ)) {
                channel.force(true);
            }
            DiskStore.syncDirectory(pending);
            Files.move(pending, dir, StandardCopyOption.ATOMIC_MOVE);
            DiskStore.syncDirectory(root);
        } catch (IOException error) {
            discardCreating(pending);
            discardCreating(dir);
            throw error;
        }
        active++;
        return id;
    }

    private void discardCreating(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) Files.delete(file);
            }
        }
        Files.delete(dir);
        DiskStore.syncDirectory(root);
    }

    public synchronized int activeUploads() { return active; }
    public synchronized long stagedBytes() { return staged; }

    public synchronized String putPart(String id, String bucket, String key, int number, InputStream input,
                                long length, String expectedHash, String checksum) throws IOException {
        if (number < 1 || number > 10000) throw new StoreException(400, "InvalidArgument", "Invalid part number");
        if (length < 0) throw new StoreException(411, "MissingContentLength", "Content-Length is required");
        if (length > store.maxObject()) throw new StoreException(413, "EntityTooLarge", "Part exceeds the object limit");
        Path dir = upload(id, bucket, key), target = part(dir, number);
        long previous = Files.exists(target) ? Files.size(target) : 0;
        if (staged - previous + length > store.maxTotal())
            throw new StoreException(507, "InsufficientStorage", "Multipart staging limit reached");
        Path pending = Files.createTempFile(store.root().resolve("pending"), "part-", ".part");
        try {
            MessageDigest sha = digest("SHA-256"), md5 = digest("MD5");
            long count = 0;
            try (var output = Files.newOutputStream(pending)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    count += n;
                    if (count > length) throw new StoreException(413, "EntityTooLarge", "Part exceeds declared size");
                    sha.update(buffer, 0, n);
                    md5.update(buffer, 0, n);
                    output.write(buffer, 0, n);
                }
            }
            if (count != length) throw new StoreException(400, "IncompleteBody", "Part length does not match Content-Length");
            byte[] actual = sha.digest();
            if (expectedHash != null && !MessageDigest.isEqual(actual, HexFormat.of().parseHex(expectedHash)))
                throw new StoreException(400, "XAmzContentSHA256Mismatch", "Part hash mismatch");
            if (checksum != null && !Base64.getEncoder().encodeToString(actual).equals(checksum))
                throw new StoreException(400, "BadDigest", "SHA-256 checksum mismatch");
            try (var channel = java.nio.channels.FileChannel.open(pending, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            staged = staged - previous + length;
            DiskStore.syncDirectory(dir);
            return SigV4.hex(md5.digest());
        } finally { Files.deleteIfExists(pending); }
    }

    public synchronized ObjectStorage.Metadata complete(String id, String bucket, String key, List<Part> parts) throws IOException {
        Path dir = upload(id, bucket, key);
        if (parts.isEmpty() || parts.size() > 10000)
            throw new StoreException(400, "InvalidPart", "No valid parts supplied");
        MessageDigest sha = digest("SHA-256");
        List<Path> paths = new ArrayList<>();
        long total = 0;
        int last = 0;
        for (Part part : parts) {
            if (part.number() <= last || part.number() > 10000)
                throw new StoreException(400, "InvalidPartOrder", "Parts must be in ascending order");
            last = part.number();
            Path file = part(dir, part.number());
            if (!Files.isRegularFile(file)) throw new StoreException(400, "InvalidPart", "Missing part");
            long length = Files.size(file);
            total += length;
            if (total > store.maxObject()) throw new StoreException(413, "EntityTooLarge", "Object exceeds the configured size limit");
            MessageDigest md5 = digest("MD5");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    sha.update(buffer, 0, n);
                    md5.update(buffer, 0, n);
                }
            }
            if (!SigV4.hex(md5.digest()).equals(part.etag().replace("\"", "")))
                throw new StoreException(400, "InvalidPart", "Part ETag mismatch");
            paths.add(file);
        }
        ObjectStorage.Metadata result;
        try (InputStream input = new PartsInput(paths)) {
            Upload upload = readUpload(dir);
            result = store.put(bucket, key, input, total, SigV4.hex(sha.digest()), null, false,
                upload.contentType(), upload.userMetadata(), upload.tags(), Map::of, upload.acl());
        }
        remove(dir);
        return result;
    }

    public synchronized void abort(String id, String bucket, String key) throws IOException {
        remove(upload(id, bucket, key));
    }

    @Override public synchronized PartPage listParts(String id, String bucket, String key,
                                                     int marker, int maxParts) throws IOException {
        Path dir = upload(id, bucket, key);
        List<PartInfo> parts = new ArrayList<>();
        boolean truncated = false;
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().matches("part-[0-9]{5}"))
                    .sorted().toList()) {
                int number = Integer.parseInt(file.getFileName().toString().substring(5));
                if (number <= marker) continue;
                if (parts.size() == maxParts) {
                    truncated = true;
                    break;
                }
                MessageDigest md5 = digest("MD5");
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[65536];
                    int count;
                    while ((count = input.read(buffer)) != -1) md5.update(buffer, 0, count);
                }
                parts.add(new PartInfo(number, Files.size(file), SigV4.hex(md5.digest()),
                    Files.getLastModifiedTime(file).toMillis()));
            }
        }
        int next = parts.isEmpty() ? marker : parts.getLast().number();
        return new PartPage(parts, next, truncated);
    }

    @Override public synchronized List<UploadInfo> listUploads(String bucket, String prefix) throws IOException {
        List<UploadInfo> uploads = new ArrayList<>();
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Upload upload = readUpload(dir);
                if (upload.bucket().equals(bucket) && upload.key().startsWith(prefix))
                    uploads.add(new UploadInfo(dir.getFileName().toString(), upload.key(),
                        Files.getLastModifiedTime(dir.resolve("manifest")).toMillis()));
            }
        }
        uploads.sort(Comparator.comparing(UploadInfo::key).thenComparing(UploadInfo::id));
        return uploads;
    }

    private Path upload(String id, String bucket, String key) throws IOException {
        if (!id.matches("[0-9a-f-]{36}")) throw new StoreException(404, "NoSuchUpload", "Upload not found");
        Path dir = root.resolve(id);
        if (!Files.isDirectory(dir)) throw new StoreException(404, "NoSuchUpload", "Upload not found");
        Upload upload = readUpload(dir);
        if (!upload.bucket().equals(bucket) || !upload.key().equals(key))
            throw new StoreException(404, "NoSuchUpload", "Upload not found");
        return dir;
    }
    private static Upload readUpload(Path dir) throws IOException {
        try (var input = new DataInputStream(Files.newInputStream(dir.resolve("manifest")))) {
            int magic = input.readInt();
            if (magic != MAGIC && magic != MAGIC_V2 && magic != MAGIC_V3)
                throw new IOException("Invalid multipart upload manifest");
            String bucket = input.readUTF(), key = input.readUTF(), type = input.readUTF();
            Map<String, String> custom = magic != MAGIC ? ObjectAttributes.decode(input.readNBytes(input.readUnsignedShort())) : Map.of();
            Map<String, String> tags = magic != MAGIC ? ObjectAttributes.decode(input.readNBytes(input.readUnsignedShort())) : Map.of();
            Map<String, String> acl = magic == MAGIC_V3 ? ObjectAttributes.decode(input.readNBytes(input.readUnsignedShort())) : Map.of();
            Upload upload = new Upload(bucket, key, type, custom, tags, acl);
            if (input.read() != -1) throw new IOException("Invalid multipart upload manifest");
            return upload;
        }
    }
    private static Path part(Path dir, int number) { return dir.resolve("part-%05d".formatted(number)); }
    private void remove(Path dir) throws IOException {
        long removed = 0;
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                if (file.getFileName().toString().matches("part-[0-9]{5}")) removed += Files.size(file);
                Files.delete(file);
            }
        }
        DiskStore.syncDirectory(dir);
        Files.delete(dir);
        DiskStore.syncDirectory(root);
        staged -= removed;
        active--;
    }
    private static MessageDigest digest(String algorithm) {
        try { return MessageDigest.getInstance(algorithm); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static final class PartsInput extends InputStream {
        private final Iterator<Path> parts;
        private InputStream current;
        PartsInput(List<Path> files) { parts = files.iterator(); }
        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            while (true) {
                if (current == null) {
                    if (!parts.hasNext()) return -1;
                    current = Files.newInputStream(parts.next());
                }
                int n = current.read(buffer, offset, length);
                if (n >= 0) return n;
                current.close();
                current = null;
            }
        }
        @Override public void close() throws IOException { if (current != null) current.close(); }
    }
}
