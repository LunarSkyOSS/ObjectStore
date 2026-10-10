package cloud.lunarsky.objectstore.client;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reported or observed operation support, separate from the caller's authorization. */
public record Capabilities(ServiceKind service, Map<String, Support> operations, Map<String, Long> limits,
                           String serviceVersion, String storageMode, boolean completeOperationInventory) {
    private static final Set<String> KNOWN_OPERATIONS = Set.of(
        "ListBuckets", "CreateBucket", "HeadBucket", "DeleteBucket", "ListObjectsV2",
        "PutObject", "GetObject", "HeadObject", "DeleteObject", "CopyObject",
        "GetObjectTagging", "PutObjectTagging", "DeleteObjectTagging",
        "CreateMultipartUpload", "UploadPart", "ListParts", "CompleteMultipartUpload",
        "AbortMultipartUpload", "ListMultipartUploads", "GetBucketVersioning",
        "PutBucketVersioning", "ListObjectVersions", "GetBucketAcl", "PutBucketAcl",
        "GetObjectAcl", "PutObjectAcl");

    /** Identifies a self-reported ObjectStore service or an unrecognized S3-compatible service. */
    public enum ServiceKind { OBJECTSTORE, UNKNOWN_S3 }

    /** Three-state feature support; a generic S3 server cannot be inferred from its hostname. */
    public enum Support { SUPPORTED, UNSUPPORTED, UNKNOWN }

    public Capabilities {
        Objects.requireNonNull(service, "service");
        operations = Map.copyOf(operations);
        limits = Map.copyOf(limits);
    }

    /** Constructs a result without a server manifest or configured limits. */
    public Capabilities(ServiceKind service, Map<String, Support> operations) {
        this(service, operations, Map.of(), null, null, false);
    }

    /** Returns support for a named operation. A missing manifest or denied probe stays UNKNOWN. */
    public Support support(String operation) {
        Support observed = operations.get(operation);
        if (observed != null) return observed;
        if (completeOperationInventory && KNOWN_OPERATIONS.contains(operation)) return Support.UNSUPPORTED;
        return Support.UNKNOWN;
    }
}
