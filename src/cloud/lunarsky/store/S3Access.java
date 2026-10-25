package cloud.lunarsky.store;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

final class S3Access {
    private final ObjectStorage store;
    private final String owner;
    private final java.util.Map<String, BucketPolicy> identityPolicies;
    private final ConcurrentHashMap<String, CachedPolicy> policies = new ConcurrentHashMap<>();
    private record CachedPolicy(String source, BucketPolicy parsed) { }

    S3Access(ObjectStorage store, String owner) {
        this(store, owner, java.util.Map.of());
    }

    S3Access(ObjectStorage store, String owner, java.util.Map<String, BucketPolicy> identityPolicies) {
        this.store = store;
        this.owner = owner;
        this.identityPolicies = java.util.Map.copyOf(identityPolicies);
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

    private void require(ObjectStorage.Bucket bucket, java.util.Map<String, String> acl,
                         String principal, int permission, String action, String key) {
        String resource = "arn:aws:s3:::" + bucket.name() + (key == null ? "" : "/" + key);
        BucketPolicy policy = policy(bucket);
        int decision = policy.decide(principal, action, resource);
        int identityDecision = principal == null ? 0 :
            identityPolicies.getOrDefault(principal, BucketPolicy.empty()).decide(principal, action, resource);
        if (decision < 0 || identityDecision < 0 ||
            !BucketSecurity.allowsAcl(bucket, acl, principal, owner, permission) &&
            identityDecision <= 0 &&
            !(decision > 0 && (principal != null || !bucket.publicAccessBlock().restrictPublicBuckets() ||
                !policy.publicAllow())))
            throw new StoreException(403, "AccessDenied", "Access denied");
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
