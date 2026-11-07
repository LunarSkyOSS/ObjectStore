package cloud.lunarsky.store;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

final class S3Access {
    record Decision(boolean allowed, String reason) { }
    private final ObjectStorage store;
    private final String owner;
    private final java.util.Map<String, BucketPolicy> configuredIdentityPolicies;
    private volatile java.util.Map<String, BucketPolicy> identityPolicies;
    private final ConcurrentHashMap<String, CachedPolicy> policies = new ConcurrentHashMap<>();
    private record CachedPolicy(String source, BucketPolicy parsed) { }

    S3Access(ObjectStorage store, String owner) {
        this(store, owner, java.util.Map.of());
    }

    S3Access(ObjectStorage store, String owner, java.util.Map<String, BucketPolicy> identityPolicies) {
        this.store = store;
        this.owner = owner;
        this.configuredIdentityPolicies = java.util.Map.copyOf(identityPolicies);
        this.identityPolicies = configuredIdentityPolicies;
    }

    synchronized void setManagedPolicies(java.util.Map<String, BucketPolicy> managed) {
        var combined = new java.util.HashMap<>(configuredIdentityPolicies);
        for (var entry : managed.entrySet())
            if (combined.putIfAbsent(entry.getKey(), entry.getValue()) != null)
                throw new IllegalArgumentException("Managed policy conflicts with a configured identity");
        identityPolicies = java.util.Map.copyOf(combined);
    }

    String owner() {
        return owner;
    }

    void requireOwner(String principal) {
        if (!owner.equals(principal)) throw new StoreException(403, "AccessDenied", "Access denied");
    }

    void requireBucket(String bucket, String principal, int permission) throws IOException {
        var current = store.bucket(bucket);
        String action = permission == Acl.READ ? "s3:ListBucket" :
            permission == Acl.READ_ACP ? "s3:GetBucketAcl" :
            permission == Acl.WRITE_ACP ? "s3:PutBucketAcl" : "s3:PutObject";
        require(current, current.acl(), principal, permission, action, null);
    }

    void requireWrite(String bucket, String key, String principal, String action) throws IOException {
        var current = store.bucket(bucket);
        require(current, current.acl(), principal, Acl.WRITE, action, key);
    }

    void requireObject(String bucket, String key, ObjectStorage.Metadata metadata,
                       String principal, int permission, String action) throws IOException {
        require(store.bucket(bucket), metadata.acl(), principal, permission, action, key);
    }

    Decision inspectBucket(String bucket, String principal) throws IOException {
        ObjectStorage.Bucket current = store.bucket(bucket);
        return decide(current, current.acl(), principal, Acl.READ, "s3:ListBucket", null);
    }

    Decision inspectWrite(String bucket, String key, String principal, String action) throws IOException {
        ObjectStorage.Bucket current = store.bucket(bucket);
        return decide(current, current.acl(), principal, Acl.WRITE, action, key);
    }

    Decision inspectObject(String bucket, String key, ObjectStorage.Metadata metadata,
                           String principal) throws IOException {
        return decide(store.bucket(bucket), metadata.acl(), principal, Acl.READ, "s3:GetObject", key);
    }

    private void require(ObjectStorage.Bucket bucket, java.util.Map<String, String> acl,
                         String principal, int permission, String action, String key) {
        if (!decide(bucket, acl, principal, permission, action, key).allowed())
            throw new StoreException(403, "AccessDenied", "Access denied");
    }

    private Decision decide(ObjectStorage.Bucket bucket, java.util.Map<String, String> acl,
                            String principal, int permission, String action, String key) {
        String resource = "arn:aws:s3:::" + bucket.name() + (key == null ? "" : "/" + key);
        BucketPolicy policy = policy(bucket);
        int decision = policy.decide(principal, action, resource);
        int identityDecision = principal == null ? 0 :
            identityPolicies.getOrDefault(principal, BucketPolicy.empty()).decide(principal, action, resource);
        if (decision < 0) return new Decision(false, "Explicit bucket policy deny");
        if (identityDecision < 0) return new Decision(false, "Explicit identity policy deny");
        if (BucketSecurity.allowsAcl(bucket, acl, principal, owner, permission))
            return new Decision(true, "Owner or ACL grants this action");
        if (identityDecision > 0) return new Decision(true, "Identity policy allows this action");
        if (decision > 0 && (principal != null || !bucket.publicAccessBlock().restrictPublicBuckets() ||
                !policy.publicAllow()))
            return new Decision(true, "Bucket policy allows this action");
        return new Decision(false, "No ACL or policy grants this action");
    }

    private BucketPolicy policy(ObjectStorage.Bucket bucket) {
        String source = bucket.policy();
        if (source == null) return BucketPolicy.empty();
        CachedPolicy cached = policies.get(bucket.name());
        if (cached != null && cached.source().equals(source)) return cached.parsed();
        BucketPolicy parsed = BucketPolicy.parse(source, bucket.name());
        policies.put(bucket.name(), new CachedPolicy(source, parsed));
        return parsed;
    }
}
