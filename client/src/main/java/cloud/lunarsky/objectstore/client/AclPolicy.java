package cloud.lunarsky.objectstore.client;

import java.util.List;
import java.util.Objects;

/** A bucket or object ACL. This is not an effective-permissions calculation. */
public record AclPolicy(String ownerId, List<Grant> grants) {
    /** A canonical user or predefined S3 group. */
    public enum GranteeType { CANONICAL_USER, GROUP }

    /** An S3 ACL permission. */
    public enum Permission { READ, WRITE, READ_ACP, WRITE_ACP, FULL_CONTROL }

    /** One ACL grant to a canonical user ID or S3 group URI. */
    public record Grant(GranteeType type, String grantee, Permission permission) {
        public Grant {
            Objects.requireNonNull(type, "type");
            if (grantee == null || grantee.isBlank()) throw new IllegalArgumentException("grantee is required");
            Objects.requireNonNull(permission, "permission");
        }
    }

    public AclPolicy {
        if (ownerId == null || ownerId.isBlank()) throw new IllegalArgumentException("owner ID is required");
        grants = List.copyOf(grants);
    }
}
