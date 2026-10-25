package cloud.lunarsky.store;

import com.sun.net.httpserver.Headers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class KeyScopes {
    private record Scope(String bucket, String prefix, Set<String> actions) {
        boolean grants(String action, String bucket, String key) {
            return this.bucket.equals(bucket) && key.startsWith(prefix) && actions.contains(action);
        }
    }

    private final String owner;
    private final Map<String, List<Scope>> grants;

    private KeyScopes(String owner, Map<String, List<Scope>> grants) {
        this.owner = owner;
        this.grants = grants;
    }

    static KeyScopes unrestricted(String owner) { return new KeyScopes(owner, null); }

    static KeyScopes fromEnvironment(Map<String, String> env, String owner, Set<String> identities)
        throws IOException {
        String file = env.get("S3_KEY_SCOPES_FILE");
        if (file == null || file.isBlank()) return unrestricted(owner);
        Map<String, List<Scope>> grants = new HashMap<>();
        List<String> lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
        if (lines.size() > 256) throw new IllegalArgumentException("Too many key scopes");
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 4 || !identities.contains(fields[0]) || fields[0].equals(owner) ||
                !fields[1].matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]") ||
                fields[2].getBytes(StandardCharsets.UTF_8).length > 1024 || fields[2].indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid key scope");
            Set<String> actions = Set.of(fields[3].split(",", -1));
            if (actions.isEmpty() || !Set.of("read", "write", "list").containsAll(actions))
                throw new IllegalArgumentException("Invalid key scope actions");
            grants.computeIfAbsent(fields[0], ignored -> new ArrayList<>())
                .add(new Scope(fields[1], fields[2], actions));
        }
        grants.replaceAll((key, value) -> List.copyOf(value));
        return new KeyScopes(owner, Map.copyOf(grants));
    }

    void require(String principal, String action, String bucket, String key) {
        if (grants == null || principal == null || owner.equals(principal)) return;
        if (grants.getOrDefault(principal, List.of()).stream().noneMatch(scope -> scope.grants(action, bucket, key)))
            throw new StoreException(403, "AccessDenied", "Access denied");
    }

    void checkObject(String principal, String bucket, String key, String method,
                     Map<String, String> query, Headers headers) {
        if (grants == null || principal == null || owner.equals(principal)) return;
        if (query.containsKey("acl") || headers.containsKey("x-amz-acl") ||
            headers.keySet().stream().anyMatch(name -> name.toLowerCase(java.util.Locale.ROOT)
                .startsWith("x-amz-grant-")))
            throw new StoreException(403, "AccessDenied", "Access denied");
        String action = method.equals("GET") || method.equals("HEAD") ? "read" : "write";
        require(principal, action, bucket, key);
        String source = headers.getFirst("x-amz-copy-source");
        if (source != null) {
            if (source.startsWith("/")) source = source.substring(1);
            int question = source.indexOf('?');
            if (question >= 0) source = source.substring(0, question);
            int slash = source.indexOf('/');
            if (slash <= 0 || slash == source.length() - 1)
                throw new StoreException(400, "InvalidArgument", "Invalid copy source");
            require(principal, "read", SigV4.decode(source.substring(0, slash)),
                SigV4.decode(source.substring(slash + 1)));
        }
    }

    void checkBucket(String principal, String bucket, String method, Map<String, String> query) {
        if (grants == null || principal == null || owner.equals(principal)) return;
        if (!method.equals("GET") || !"2".equals(query.get("list-type")) ||
            !query.containsKey("prefix"))
            throw new StoreException(403, "AccessDenied", "Access denied");
        require(principal, "list", bucket, query.get("prefix"));
    }
}
