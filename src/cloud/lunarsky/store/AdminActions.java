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
        String bucket = form.get("bucket");
        if (action.equals("create-bucket")) {
            bucket = required(form, "bucket", 63);
            boolean wizard = "wizard".equals(form.get("setup"));
            String versioning = form.getOrDefault("versioning", "off");
            if (wizard && !versioning.equals("off") && !versioning.equals("enabled"))
                invalid("Invalid initial versioning setting");
            ObjectStorage.ObjectOwnership ownership = wizard ?
                BucketSecurity.ownership(form.getOrDefault("ownership", "BucketOwnerEnforced")) :
                ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED;
            ObjectStorage.PublicAccessBlock publicBlock = wizard ? new ObjectStorage.PublicAccessBlock(
                form.containsKey("blockPublicAcls"), form.containsKey("ignorePublicAcls"),
                form.containsKey("blockPublicPolicy"), form.containsKey("restrictPublicBuckets")) :
                ObjectStorage.PublicAccessBlock.secure();
            if (wizard && !publicBlock.equals(ObjectStorage.PublicAccessBlock.secure()) &&
                !"yes".equals(form.get("acknowledgePublic")))
                invalid("Confirm that relaxing public access controls may expose this bucket");
            store.createBucket(bucket);
            if (wizard) {
                if (versioning.equals("enabled"))
                    store.setVersioning(bucket, ObjectStorage.VersioningState.ENABLED);
                if (ownership != ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED)
                    store.setBucketOwnership(bucket, ownership);
                if (!publicBlock.equals(ObjectStorage.PublicAccessBlock.secure()))
                    store.setPublicAccessBlock(bucket, publicBlock);
            }
            return AdminBrowser.bucketUrl(bucket, "", null);
        }
        bucket = required(form, "bucket", 63);
        if (action.equals("rename-bucket")) {
            confirm(form, bucket);
            String newName = required(form, "newName", 63);
            String currentName = bucket;
            if (managed != null && managed.list().stream().anyMatch(key -> key.bucket().equals(currentName)))
                throw new StoreException(409, "BucketHasScopedKeys",
                    "Move or remove scoped access keys for this bucket before renaming it");
            store.renameBucket(bucket, newName);
            return AdminBrowser.bucketUrl(newName, "", null);
        }
        if (action.equals("delete-bucket")) {
            confirm(form, bucket);
            store.deleteBucket(bucket);
            return AdminConsole.PATH + "/buckets";
        }
        ObjectStorage.Bucket current = store.bucket(bucket);
        switch (action) {
            case "delete-object" -> {
                String key = required(form, "key", 1024);
                if (!key.equals(form.get("confirm"))) invalid("Type the object key to confirm");
                String version = form.getOrDefault("version", "");
                if (version.length() > 256) invalid("Invalid object version");
                if (!version.isEmpty() && !version.equals(form.get("confirmVersion")))
                    invalid("Type the version ID to confirm permanent deletion");
                store.delete(bucket, key, version.isEmpty() ? null : version);
                return AdminBrowser.bucketUrl(bucket, key.contains("/") ?
                    key.substring(0, key.lastIndexOf('/') + 1) : "", null);
            }
            case "versioning" -> {
                String state = required(form, "state", 16);
                if (!state.equals("ENABLED") && !state.equals("SUSPENDED")) invalid("Invalid versioning state");
                store.setVersioning(bucket, ObjectStorage.VersioningState.valueOf(state));
            }
            case "ownership" -> {
                confirm(form, bucket);
                String value = required(form, "ownership", 32);
                store.setBucketOwnership(bucket, BucketSecurity.ownership(value));
            }
            case "public-access" -> {
                ObjectStorage.PublicAccessBlock block = new ObjectStorage.PublicAccessBlock(
                    form.containsKey("blockPublicAcls"), form.containsKey("ignorePublicAcls"),
                    form.containsKey("blockPublicPolicy"), form.containsKey("restrictPublicBuckets"));
                ObjectStorage.PublicAccessBlock previous = current.publicAccessBlock();
                if (previous.blockPublicAcls() && !block.blockPublicAcls() ||
                    previous.ignorePublicAcls() && !block.ignorePublicAcls() ||
                    previous.blockPublicPolicy() && !block.blockPublicPolicy() ||
                    previous.restrictPublicBuckets() && !block.restrictPublicBuckets()) confirm(form, bucket);
                store.setPublicAccessBlock(bucket, block);
            }
            case "policy" -> {
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
            case "bucket-grant" -> {
                Map<String, String> acl = updateGrants(current.acl(), form, identities);
                if (newPublicGrant(current.acl(), acl)) confirm(form, bucket);
                BucketSecurity.checkAcl(current, new Headers(), acl, true);
                store.setBucketAcl(bucket, acl);
            }
            case "object-grant" -> {
                String key = required(form, "key", 1024);
                String version = form.getOrDefault("version", "");
                if (version.length() > 256) invalid("Invalid object version");
                String versionId = version.isEmpty() ? null : version;
                ObjectStorage.Metadata metadata;
                try (ObjectStorage.OpenObject opened = store.open(bucket, key, versionId)) {
                    metadata = opened.metadata();
                }
                Map<String, String> acl = updateGrants(metadata.acl(), form, identities);
                if (newPublicGrant(metadata.acl(), acl)) confirm(form, bucket);
                BucketSecurity.checkAcl(current, new Headers(), acl, true);
                store.setObjectAcl(bucket, key, versionId, acl);
                return AdminConsole.PATH + "/object?bucket=" + AdminBrowser.encoded(bucket) +
                    "&key=" + AdminBrowser.encoded(key) +
                    (versionId == null ? "" : "&version=" + AdminBrowser.encoded(versionId));
            }
            case "object-tag" -> {
                String key = required(form, "key", 1024);
                String tag = required(form, "tag", 128);
                String version = form.getOrDefault("version", "");
                if (version.length() > 256) invalid("Invalid object version");
                String versionId = version.isEmpty() ? null : version;
                Map<String, String> tags = new HashMap<>(store.tags(bucket, key, versionId));
                if ("remove".equals(form.get("operation"))) tags.remove(tag);
                else if ("add".equals(form.get("operation"))) {
                    String value = form.get("value");
                    if (value == null || value.length() > 256) invalid("Invalid value");
                    tags.put(tag, value);
                }
                else invalid("Invalid tag operation");
                ObjectAttributes.validateTags(tags);
                store.setTags(bucket, key, versionId, Map.copyOf(tags));
                return AdminConsole.PATH + "/object?bucket=" + AdminBrowser.encoded(bucket) +
                    "&key=" + AdminBrowser.encoded(key) +
                    (versionId == null ? "" : "&version=" + AdminBrowser.encoded(versionId));
            }
            default -> invalid("Unknown admin action");
        }
        return AdminBrowser.bucketUrl(bucket, "", null) + "#settings";
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
