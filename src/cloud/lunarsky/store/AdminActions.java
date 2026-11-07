package cloud.lunarsky.store;

import com.sun.net.httpserver.Headers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class AdminActions {
    private AdminActions() { }

    static String apply(ObjectStorage store, String action, Map<String, String> form,
                        Set<String> identities, ManagedCredentials managed) throws IOException {
        String bucket = required(form, "bucket", 63);
        return switch (action) {
            case "create-bucket" -> createBucket(store, bucket, form);
            case "rename-bucket" -> renameBucket(store, bucket, form, managed);
            case "delete-bucket" -> deleteBucket(store, bucket, form);
            default -> updateBucket(store, bucket, action, form, identities);
        };
    }

    private static String createBucket(ObjectStorage store, String bucket, Map<String, String> form)
            throws IOException {
        boolean wizard = "wizard".equals(form.get("setup"));
        String versioning = form.getOrDefault("versioning", "off");
        if (wizard && !versioning.equals("off") && !versioning.equals("enabled"))
            invalid("Invalid initial versioning setting");
        ObjectStorage.ObjectOwnership ownership = wizard ?
            BucketSecurity.ownership(form.getOrDefault("ownership", "BucketOwnerEnforced")) :
            ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED;
        ObjectStorage.PublicAccessBlock publicBlock = wizard ? publicAccessBlock(form) :
            ObjectStorage.PublicAccessBlock.secure();
        if (wizard && !publicBlock.equals(ObjectStorage.PublicAccessBlock.secure()) &&
            !"yes".equals(form.get("acknowledgePublic")))
            invalid("Confirm that relaxing public access controls may expose this bucket");
        store.createBucket(bucket);
        if (wizard) configureCreatedBucket(store, bucket, versioning, ownership, publicBlock);
        return AdminBrowser.bucketUrl(bucket, "", null);
    }

    private static void configureCreatedBucket(ObjectStorage store, String bucket, String versioning,
            ObjectStorage.ObjectOwnership ownership, ObjectStorage.PublicAccessBlock publicBlock)
            throws IOException {
        if (versioning.equals("enabled"))
            store.setVersioning(bucket, ObjectStorage.VersioningState.ENABLED);
        if (ownership != ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED)
            store.setBucketOwnership(bucket, ownership);
        if (!publicBlock.equals(ObjectStorage.PublicAccessBlock.secure()))
            store.setPublicAccessBlock(bucket, publicBlock);
    }

    private static String renameBucket(ObjectStorage store, String bucket, Map<String, String> form,
                                       ManagedCredentials managed) throws IOException {
        confirm(form, bucket);
        String newName = required(form, "newName", 63);
        if (managed != null && managed.list().stream().anyMatch(key -> key.bucket().equals(bucket)))
            throw new StoreException(409, "BucketHasScopedKeys",
                "Move or remove scoped access keys for this bucket before renaming it");
        store.renameBucket(bucket, newName);
        return AdminBrowser.bucketUrl(newName, "", null);
    }

    private static String deleteBucket(ObjectStorage store, String bucket, Map<String, String> form)
            throws IOException {
        confirm(form, bucket);
        store.deleteBucket(bucket);
        return AdminConsole.PATH + "/buckets";
    }

    private static String updateBucket(ObjectStorage store, String bucket, String action,
            Map<String, String> form, Set<String> identities) throws IOException {
        ObjectStorage.Bucket current = store.bucket(bucket);
        switch (action) {
            case "delete-object" -> { return deleteObject(store, bucket, form); }
            case "versioning" -> setVersioning(store, bucket, form);
            case "ownership" -> setOwnership(store, bucket, form);
            case "public-access" -> setPublicAccess(store, bucket, current, form);
            case "policy" -> setPolicy(store, bucket, current, form);
            case "bucket-grant" -> setBucketGrant(store, bucket, current, form, identities);
            case "object-grant" -> { return setObjectGrant(store, bucket, current, form, identities); }
            case "object-tag" -> { return setObjectTag(store, bucket, form); }
            default -> invalid("Unknown admin action");
        }
        return AdminBrowser.bucketUrl(bucket, "", null) + "#settings";
    }

    private static String deleteObject(ObjectStorage store, String bucket, Map<String, String> form)
            throws IOException {
        String key = required(form, "key", 1024);
        if (!key.equals(form.get("confirm"))) invalid("Type the object key to confirm");
        String version = version(form);
        if (version != null && !version.equals(form.get("confirmVersion")))
            invalid("Type the version ID to confirm permanent deletion");
        store.delete(bucket, key, version);
        return AdminBrowser.bucketUrl(bucket, key.contains("/") ?
            key.substring(0, key.lastIndexOf('/') + 1) : "", null);
    }

    private static void setVersioning(ObjectStorage store, String bucket, Map<String, String> form)
            throws IOException {
        String state = required(form, "state", 16);
        if (!state.equals("ENABLED") && !state.equals("SUSPENDED")) invalid("Invalid versioning state");
        store.setVersioning(bucket, ObjectStorage.VersioningState.valueOf(state));
    }

    private static void setOwnership(ObjectStorage store, String bucket, Map<String, String> form)
            throws IOException {
        confirm(form, bucket);
        String value = required(form, "ownership", 32);
        store.setBucketOwnership(bucket, BucketSecurity.ownership(value));
    }

    private static void setPublicAccess(ObjectStorage store, String bucket, ObjectStorage.Bucket current,
                                       Map<String, String> form) throws IOException {
        ObjectStorage.PublicAccessBlock block = publicAccessBlock(form);
        ObjectStorage.PublicAccessBlock previous = current.publicAccessBlock();
        if (previous.blockPublicAcls() && !block.blockPublicAcls() ||
            previous.ignorePublicAcls() && !block.ignorePublicAcls() ||
            previous.blockPublicPolicy() && !block.blockPublicPolicy() ||
            previous.restrictPublicBuckets() && !block.restrictPublicBuckets()) confirm(form, bucket);
        store.setPublicAccessBlock(bucket, block);
    }

    private static ObjectStorage.PublicAccessBlock publicAccessBlock(Map<String, String> form) {
        return new ObjectStorage.PublicAccessBlock(
            form.containsKey("blockPublicAcls"), form.containsKey("ignorePublicAcls"),
            form.containsKey("blockPublicPolicy"), form.containsKey("restrictPublicBuckets"));
    }

    private static void setPolicy(ObjectStorage store, String bucket, ObjectStorage.Bucket current,
                                  Map<String, String> form) throws IOException {
        String policy = form.getOrDefault("policy", "").trim();
        if (policy.getBytes(StandardCharsets.UTF_8).length > 20480) invalid("Policy is too large");
        if (!policy.isEmpty()) {
            BucketPolicy parsed = BucketPolicy.parse(policy, bucket);
            if (current.publicAccessBlock().blockPublicPolicy() && parsed.publicAllow())
                throw new StoreException(403, "AccessDenied", "Public bucket policies are blocked");
            if (parsed.publicAllow()) confirm(form, bucket);
        }
        store.setBucketPolicy(bucket, policy.isEmpty() ? null : policy);
    }

    private static void setBucketGrant(ObjectStorage store, String bucket, ObjectStorage.Bucket current,
            Map<String, String> form, Set<String> identities) throws IOException {
        Map<String, String> acl = updateGrants(current.acl(), form, identities);
        if (newPublicGrant(current.acl(), acl)) confirm(form, bucket);
        BucketSecurity.checkAcl(current, new Headers(), acl, true);
        store.setBucketAcl(bucket, acl);
    }

    private static String setObjectGrant(ObjectStorage store, String bucket, ObjectStorage.Bucket current,
            Map<String, String> form, Set<String> identities) throws IOException {
        String key = required(form, "key", 1024);
        String version = version(form);
        ObjectStorage.Metadata metadata;
        try (ObjectStorage.OpenObject opened = store.open(bucket, key, version)) {
            metadata = opened.metadata();
        }
        Map<String, String> acl = updateGrants(metadata.acl(), form, identities);
        if (newPublicGrant(metadata.acl(), acl)) confirm(form, bucket);
        BucketSecurity.checkAcl(current, new Headers(), acl, true);
        store.setObjectAcl(bucket, key, version, acl);
        return objectUrl(bucket, key, version);
    }

    private static String setObjectTag(ObjectStorage store, String bucket, Map<String, String> form)
            throws IOException {
        String key = required(form, "key", 1024);
        String tag = required(form, "tag", 128);
        String version = version(form);
        Map<String, String> tags = new HashMap<>(store.tags(bucket, key, version));
        if ("remove".equals(form.get("operation"))) tags.remove(tag);
        else if ("add".equals(form.get("operation"))) {
            String value = form.get("value");
            if (value == null || value.length() > 256) invalid("Invalid value");
            tags.put(tag, value);
        } else invalid("Invalid tag operation");
        ObjectAttributes.validateTags(tags);
        store.setTags(bucket, key, version, Map.copyOf(tags));
        return objectUrl(bucket, key, version);
    }

    private static String version(Map<String, String> form) {
        String version = form.getOrDefault("version", "");
        if (version.length() > 256) invalid("Invalid object version");
        return version.isEmpty() ? null : version;
    }

    private static String objectUrl(String bucket, String key, String version) {
        return AdminConsole.PATH + "/object?bucket=" + AdminBrowser.encoded(bucket) +
            "&key=" + AdminBrowser.encoded(key) +
            (version == null ? "" : "&version=" + AdminBrowser.encoded(version));
    }

    private static Map<String, String> updateGrants(Map<String, String> current,
                                                      Map<String, String> form, Set<String> identities) {
        String principal = required(form, "principal", 128);
        if (principal.equals("AllUsers")) principal = Acl.ALL_USERS;
        if (principal.equals("AuthenticatedUsers")) principal = Acl.AUTHENTICATED_USERS;
        Map<String, String> updated = new HashMap<>(current);
        if ("remove".equals(form.get("operation"))) updated.remove(principal);
        else if ("add".equals(form.get("operation"))) {
            int bits = switch (required(form, "permission", 16)) {
                case "READ" -> Acl.READ;
                case "WRITE" -> Acl.WRITE;
                case "READ_ACP" -> Acl.READ_ACP;
                case "WRITE_ACP" -> Acl.WRITE_ACP;
                case "FULL_CONTROL" -> Acl.FULL_CONTROL;
                default -> throw new StoreException(400, "InvalidArgument", "Invalid ACL permission");
            };
            int existing = Integer.parseInt(updated.getOrDefault(principal, "0"));
            updated.put(principal, Integer.toString(existing | bits));
        } else invalid("Invalid grant operation");
        return Acl.validate(updated, identities);
    }

    private static boolean newPublicGrant(Map<String, String> before, Map<String, String> after) {
        return java.util.List.of(Acl.ALL_USERS, Acl.AUTHENTICATED_USERS).stream().anyMatch(
            principal -> !before.getOrDefault(principal, "0").equals(after.getOrDefault(principal, "0")) &&
                Integer.parseInt(after.getOrDefault(principal, "0")) != 0);
    }

    private static String required(Map<String, String> form, String field, int maxLength) {
        String value = form.get(field);
        if (value == null || value.isBlank() || value.length() > maxLength || value.indexOf('\0') >= 0)
            throw new StoreException(400, "InvalidArgument", "Invalid " + field);
        return value;
    }

    private static void confirm(Map<String, String> form, String bucket) {
        if (!bucket.equals(form.get("confirm")))
            throw new StoreException(400, "InvalidArgument", "Type the bucket name to confirm");
    }

    private static void invalid(String message) {
        throw new StoreException(400, "InvalidArgument", message);
    }
}
