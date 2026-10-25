package cloud.lunarsky.store;

import java.io.IOException;

final class S3Access {
    private final ObjectStorage store;
    private final String owner;

    S3Access(ObjectStorage store, String owner) {
        this.store = store;
        this.owner = owner;
    }

    String owner() {
        return owner;
    }

    void requireOwner(String principal) {
        if (!owner.equals(principal)) throw new StoreException(403, "AccessDenied", "Access denied");
    }

    void requireBucket(String bucket, String principal, int permission) throws IOException {
        Acl.require(store.bucket(bucket).acl(), principal, owner, permission);
    }
}
