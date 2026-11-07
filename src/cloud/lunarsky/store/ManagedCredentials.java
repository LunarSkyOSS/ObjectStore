package cloud.lunarsky.store;

import com.sun.net.httpserver.Headers;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

final class ManagedCredentials {
    record Key(String id, String secret, String bucket, String prefix,
               Set<String> actions, boolean enabled) { }

    private static final byte[] MAGIC = { 'O', 'S', 'K', '1' };
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> ACTIONS = Set.of("read", "write", "list");
    private final Path path;
    private final SecretKeySpec master;
    private final Set<String> reserved;
    private volatile Map<String, Key> keys;

    private ManagedCredentials(Path path, byte[] master, Set<String> reserved) throws IOException {
        this.path = path;
        this.master = new SecretKeySpec(master, "AES");
        this.reserved = Set.copyOf(reserved);
        java.util.Arrays.fill(master, (byte) 0);
        this.keys = load();
    }

    static ManagedCredentials fromEnvironment(Map<String, String> env, ObjectStorage store,
                                              Set<String> reserved) throws IOException {
        String configured = env.get("S3_MANAGED_KEYS_MASTER_FILE");
        if (configured == null || configured.isBlank()) return null;
        if (!(store instanceof DiskStore))
            throw new IllegalArgumentException("Managed credentials require disk mode");
        byte[] master;
        try {
            master = HexFormat.of().parseHex(Files.readString(Path.of(configured)).trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid managed credentials master key", error);
        }
        if (master.length != 32)
            throw new IllegalArgumentException("Managed credentials master key must contain 32 bytes");
        return new ManagedCredentials(Path.of(env.getOrDefault("DATA_DIR", "/data"))
            .resolve("managed-credentials.bin"), master, reserved);
    }

    List<Key> list() { return keys.values().stream().sorted(java.util.Comparator.comparing(Key::id)).toList(); }
    boolean contains(String principal) { return principal != null && keys.containsKey(principal); }

    Map<String, String> identities() {
        Map<String, String> active = new HashMap<>();
        keys.values().stream().filter(Key::enabled).forEach(key -> active.put(key.id(), key.secret()));
        return Map.copyOf(active);
    }

    Map<String, BucketPolicy> policies() {
        Map<String, BucketPolicy> active = new HashMap<>();
        for (Key key : keys.values()) {
            if (!key.enabled()) continue;
            List<String> objectActions = new ArrayList<>();
            if (key.actions().contains("read")) {
                objectActions.add("s3:GetObject");
                objectActions.add("s3:GetObjectVersion");
            }
            if (key.actions().contains("write")) {
                objectActions.add("s3:PutObject");
                objectActions.add("s3:DeleteObject");
                objectActions.add("s3:DeleteObjectVersion");
                objectActions.add("s3:AbortMultipartUpload");
            }
            String bucketArn = "arn:aws:s3:::" + key.bucket();
            StringBuilder statements = new StringBuilder();
            if (key.actions().contains("list"))
                statements.append("{\"Effect\":\"Allow\",\"Action\":\"s3:ListBucket\",\"Resource\":\"")
                    .append(bucketArn).append("\"}");
            if (!objectActions.isEmpty()) {
                if (!statements.isEmpty()) statements.append(',');
                statements.append("{\"Effect\":\"Allow\",\"Action\":[");
                for (int i = 0; i < objectActions.size(); i++) {
                    if (i > 0) statements.append(',');
                    statements.append('"').append(objectActions.get(i)).append('"');
                }
                statements.append("],\"Resource\":\"").append(bucketArn).append('/')
                    .append(key.prefix()).append("*\"}");
            }
            active.put(key.id(), BucketPolicy.parseIdentity(
                "{\"Version\":\"2012-10-17\",\"Statement\":[" + statements + "]}"));
        }
        return Map.copyOf(active);
    }

    synchronized Key create(String bucket, String prefix, Set<String> actions) throws IOException {
        if (keys.size() >= 64) invalid("Too many managed access keys");
        validateScope(bucket, prefix, actions);
        byte[] random = new byte[24];
        String id;
        do {
            RANDOM.nextBytes(random);
            id = HexFormat.of().withUpperCase().formatHex(random, 0, 16);
        } while (reserved.contains(id) || keys.containsKey(id));
        RANDOM.nextBytes(random);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(random) +
            Base64.getUrlEncoder().withoutPadding().encodeToString(randomToken());
        Key key = new Key(id, secret, bucket, prefix, Set.copyOf(actions), true);
        Map<String, Key> updated = new HashMap<>(keys);
        updated.put(id, key);
        persist(updated);
        keys = Map.copyOf(updated);
        return key;
    }

    synchronized void update(String id, String bucket, String prefix, Set<String> actions) throws IOException {
        Key old = key(id);
        validateScope(bucket, prefix, actions);
        Map<String, Key> updated = new HashMap<>(keys);
        updated.put(id, new Key(id, old.secret(), bucket, prefix, Set.copyOf(actions), old.enabled()));
        persist(updated);
        keys = Map.copyOf(updated);
    }

    private static void validateScope(String bucket, String prefix, Set<String> actions) {
        if (bucket == null || prefix == null || actions == null ||
            !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]") ||
            prefix.length() > 256 || !prefix.matches("[A-Za-z0-9/_-]*") ||
            actions.isEmpty() || !ACTIONS.containsAll(actions))
            invalid("Invalid key scope");
    }

    synchronized void setEnabled(String id, boolean enabled) throws IOException {
        Key old = key(id);
        Map<String, Key> updated = new HashMap<>(keys);
        updated.put(id, new Key(id, old.secret(), old.bucket(), old.prefix(), old.actions(), enabled));
        persist(updated);
        keys = Map.copyOf(updated);
    }

    synchronized void remove(String id) throws IOException {
        key(id);
        Map<String, Key> updated = new HashMap<>(keys);
        updated.remove(id);
        persist(updated);
        keys = Map.copyOf(updated);
    }

    void checkBucket(String principal, String bucket, String method, Map<String, String> query) {
        Key key = key(principal);
        boolean head = method.equals("HEAD") &&
            (query.isEmpty() || query.size() == 1 && "HeadBucket".equals(query.get("x-id")));
        boolean list = method.equals("GET") && "2".equals(query.get("list-type")) &&
            query.getOrDefault("prefix", "").startsWith(key.prefix());
        if (!key.enabled() || !key.bucket().equals(bucket) || !key.actions().contains("list") ||
            !(head || list))
            denied();
    }

    void checkObject(String principal, String bucket, String object, String method,
                     Map<String, String> query, Headers headers) {
        Key key = key(principal);
        String action = method.equals("GET") || method.equals("HEAD") ? "read" : "write";
        if (!key.enabled() || !key.bucket().equals(bucket) || !object.startsWith(key.prefix()) ||
            !key.actions().contains(action) || query.containsKey("acl") ||
            headers.containsKey("x-amz-acl") || headers.keySet().stream().anyMatch(name ->
                name.toLowerCase(java.util.Locale.ROOT).startsWith("x-amz-grant-"))) denied();
        String source = headers.getFirst("x-amz-copy-source");
        if (source != null) {
            if (source.startsWith("/")) source = source.substring(1);
            int question = source.indexOf('?');
            if (question >= 0) source = source.substring(0, question);
            int slash = source.indexOf('/');
            if (slash < 1 || !key.actions().contains("read") ||
                !key.bucket().equals(SigV4.decode(source.substring(0, slash))) ||
                !SigV4.decode(source.substring(slash + 1)).startsWith(key.prefix())) denied();
        }
    }

    private Key key(String id) {
        Key key = keys.get(id);
        if (key == null) throw new StoreException(404, "NoSuchKey", "Access key not found");
        return key;
    }

    private Map<String, Key> load() throws IOException {
        if (!Files.exists(path)) return Map.of();
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < MAGIC.length + 12 + 16 || bytes.length > 65536 ||
            !java.util.Arrays.equals(MAGIC, java.util.Arrays.copyOf(bytes, MAGIC.length)))
            throw new IOException("Invalid managed credential store");
        byte[] nonce = java.util.Arrays.copyOfRange(bytes, MAGIC.length, MAGIC.length + 12);
        byte[] plain;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, master, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(MAGIC);
            plain = cipher.doFinal(bytes, MAGIC.length + 12, bytes.length - MAGIC.length - 12);
        } catch (GeneralSecurityException error) {
            throw new IOException("Managed credential store could not be decrypted", error);
        }
        Map<String, Key> loaded = new HashMap<>();
        for (String line : new String(plain, StandardCharsets.UTF_8).split("\n")) {
            if (line.isEmpty()) continue;
            String[] fields = line.split("\t", -1);
            if (fields.length != 6 || !fields[0].matches("[A-Za-z0-9]{16,128}") ||
                fields[1].length() < 32 || !fields[2].matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]") ||
                !fields[3].matches("[A-Za-z0-9/_-]*") || fields[3].length() > 256 ||
                !Set.of("true", "false").contains(fields[5]) || reserved.contains(fields[0]))
                throw new IOException("Invalid managed credential entry");
            Set<String> actions;
            try { actions = Set.of(fields[4].split(",")); }
            catch (IllegalArgumentException error) { throw new IOException("Invalid managed credential entry", error); }
            if (actions.isEmpty() || !ACTIONS.containsAll(actions) || loaded.size() >= 64 ||
                loaded.putIfAbsent(fields[0], new Key(fields[0], fields[1], fields[2], fields[3],
                    actions, Boolean.parseBoolean(fields[5]))) != null)
                throw new IOException("Invalid managed credential entry");
        }
        java.util.Arrays.fill(plain, (byte) 0);
        return Map.copyOf(loaded);
    }

    private void persist(Map<String, Key> updated) throws IOException {
        StringBuilder contents = new StringBuilder();
        updated.values().stream().sorted(java.util.Comparator.comparing(Key::id)).forEach(key ->
            contents.append(key.id()).append('\t').append(key.secret()).append('\t')
                .append(key.bucket()).append('\t').append(key.prefix()).append('\t')
                .append(String.join(",", key.actions().stream().sorted().toList())).append('\t')
                .append(key.enabled()).append('\n'));
        byte[] nonce = randomToken();
        byte[] encrypted;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, master, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(MAGIC);
            encrypted = cipher.doFinal(contents.toString().getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException error) {
            throw new IOException("Could not encrypt managed credentials", error);
        }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "managed-credentials-", ".tmp");
        try {
            Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                writeAll(channel, MAGIC);
                writeAll(channel, nonce);
                writeAll(channel, encrypted);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
                directory.force(true);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeAll(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static byte[] randomToken() {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    private static void invalid(String message) { throw new StoreException(400, "InvalidArgument", message); }
    private static void denied() { throw new StoreException(403, "AccessDenied", "Access denied"); }
}
