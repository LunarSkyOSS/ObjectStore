package cloud.lunarsky.store;

import com.sun.net.httpserver.Headers;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

final class AdminAccessCheck {
    record Result(boolean allowed, String stage, String explanation) { }

    private final ObjectStorage store;
    private final S3Access access;
    private final KeyScopes scopes;
    private final Set<String> identities;
    private final ManagedCredentials managed;

    AdminAccessCheck(ObjectStorage store, S3Access access, KeyScopes scopes,
                     Set<String> identities, ManagedCredentials managed) {
        this.store = store;
        this.access = access;
        this.scopes = scopes;
        this.identities = identities;
        this.managed = managed;
    }

    Result check(String principal, String operation, String bucket, String key) throws IOException {
        if (principal == null || !identities.contains(principal))
            return new Result(false, "Authentication", "Access key is unknown or disabled");
        if (operation == null || !Set.of("list", "read", "write", "delete").contains(operation))
            throw new StoreException(400, "InvalidArgument", "Choose a supported operation");
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]"))
            throw new StoreException(400, "InvalidArgument", "Choose a valid bucket");
        if (key != null && key.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024)
            throw new StoreException(400, "InvalidArgument", "Object key or prefix is too long");
        if (!operation.equals("list") && (key == null || key.isEmpty() || key.length() > 1024))
            throw new StoreException(400, "InvalidArgument", "Enter an object key");
        String method = switch (operation) {
            case "read" -> "GET";
            case "write" -> "PUT";
            case "delete" -> "DELETE";
            default -> "GET";
        };
        try {
            if (operation.equals("list")) {
                Map<String, String> query = Map.of("list-type", "2", "prefix", key == null ? "" : key);
                if (managed != null && managed.contains(principal))
                    managed.checkBucket(principal, bucket, method, query);
                else scopes.checkBucket(principal, bucket, method, query);
            } else {
                if (managed != null && managed.contains(principal))
                    managed.checkObject(principal, bucket, key, method, Map.of(), new Headers());
                else scopes.checkObject(principal, bucket, key, method, Map.of(), new Headers());
            }
        } catch (StoreException denied) {
            if (denied.status != 403) throw denied;
            return new Result(false, "Key scope", "Key scope does not allow this bucket, prefix, or operation");
        }
        S3Access.Decision decision;
        if (operation.equals("list")) {
            decision = access.inspectBucket(bucket, principal);
        } else if (operation.equals("read")) {
            ObjectStorage.ListPage page = store.list(bucket, key, "", 1, null);
            if (page.objects().isEmpty() || !key.equals(page.objects().get(0).key()))
                return new Result(false, "Object", "Current object does not exist");
            decision = access.inspectObject(bucket, key, page.objects().get(0).metadata(), principal);
        } else {
            decision = access.inspectWrite(bucket, key, principal,
                operation.equals("delete") ? "s3:DeleteObject" : "s3:PutObject");
        }
        return new Result(decision.allowed(), "Bucket and identity policy", decision.reason());
    }
}
