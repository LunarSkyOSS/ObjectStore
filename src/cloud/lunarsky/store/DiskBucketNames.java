package cloud.lunarsky.store;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class DiskBucketNames implements ObjectStorage, MultipartStorage {
    private final DiskStore store;
    private final MultipartStore uploads;
    private final BucketNameCatalog catalog;
    private volatile Map<String, String> aliases;

    DiskBucketNames(DiskStore store, MultipartStore uploads) throws IOException {
        this.store = store;
        this.uploads = uploads;
        catalog = new BucketNameCatalog(store);
        aliases = catalog.load();
    }

    private static boolean valid(String name) {
        return name != null && name.matches("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");
    }

    private String physical(String name) {
        Map<String, String> current = aliases;
        String found = current.get(name);
        if (found != null) return found;
        if (current.containsValue(name))
            throw new StoreException(404, "NoSuchBucket", "Bucket not found");
        return name;
    }

    private String visible(String physical) {
        for (var entry : aliases.entrySet())
            if (entry.getValue().equals(physical)) return entry.getKey();
        return physical;
    }

    private static String policyName(String policy, String from, String to) {
        if (policy == null) return null;
        String arn = "arn:aws:s3:::";
        return policy.replace(arn + from + "/", arn + to + "/")
            .replace(arn + from + "\"", arn + to + "\"");
    }

    private Bucket visible(Bucket bucket) {
        String name = visible(bucket.name());
        return new Bucket(name, bucket.created(), bucket.versioning(), bucket.acl(),
            bucket.ownership(), bucket.publicAccessBlock(),
            policyName(bucket.policy(), bucket.name(), name));
    }

    private Metadata visible(Metadata metadata) {
        if (metadata == null) return null;
        return new Metadata(metadata.length(), metadata.modified(), metadata.etag(), metadata.sha256(),
            visible(metadata.bucket()), metadata.key(), metadata.contentType(), metadata.userMetadata(),
            metadata.tags(), metadata.versionId(), metadata.checksums(), metadata.acl());
    }

    private void save(Map<String, String> next) throws IOException {
        catalog.save(next);
        aliases = Map.copyOf(next);
    }

    @Override public synchronized void renameBucket(String oldName, String newName) throws IOException {
        if (!valid(newName)) throw new StoreException(400, "InvalidBucketName", "Invalid bucket name");
        String underlying = physical(oldName);
        store.bucket(underlying);
        if (oldName.equals(newName)) throw new StoreException(400, "InvalidArgument", "Choose a different name");
        if (aliases.containsKey(newName) || store.buckets().stream().anyMatch(b -> b.name().equals(newName)))
            throw new StoreException(409, "BucketAlreadyExists", "Bucket name is already in use");
        Map<String, String> next = new HashMap<>(aliases);
        next.remove(oldName);
        next.put(newName, underlying);
        save(next);
    }

    @Override public Metadata put(String bucket, String key, InputStream input, long length,
                                  String expectedHash, String checksum, boolean createOnly,
                                  String contentType, Map<String, String> userMetadata,
                                  Map<String, String> tags, Supplier<Map<String, String>> checksums,
                                  Map<String, String> acl) throws IOException {
        return visible(store.put(physical(bucket), key, input, length, expectedHash, checksum,
            createOnly, contentType, userMetadata, tags, checksums, acl));
    }

    @Override public Metadata replaceVersion(String bucket, String key, byte[] content, Metadata expected)
            throws IOException {
        return visible(store.replaceVersion(physical(bucket), key, content, expected));
    }

    @Override public OpenObject open(String bucket, String key) throws IOException {
        return open(bucket, key, null);
    }

    @Override public OpenObject open(String bucket, String key, String versionId) throws IOException {
        OpenObject opened = store.open(physical(bucket), key, versionId);
        return new OpenObject(visible(opened.metadata()), opened.stream());
    }

    @Override public Map<String, String> tags(String bucket, String key) throws IOException {
        return store.tags(physical(bucket), key);
    }

    @Override public Map<String, String> tags(String bucket, String key, String versionId) throws IOException {
        return store.tags(physical(bucket), key, versionId);
    }

    @Override public void setTags(String bucket, String key, Map<String, String> tags) throws IOException {
        store.setTags(physical(bucket), key, tags);
    }

    @Override public void setTags(String bucket, String key, String versionId,
                                  Map<String, String> tags) throws IOException {
        store.setTags(physical(bucket), key, versionId, tags);
    }

    @Override public void delete(String bucket, String key) throws IOException {
        store.delete(physical(bucket), key);
    }

    @Override public DeleteResult delete(String bucket, String key, String versionId) throws IOException {
        return store.delete(physical(bucket), key, versionId);
    }

    @Override public void setVersioning(String bucket, VersioningState state) throws IOException {
        store.setVersioning(physical(bucket), state);
    }

    @Override public VersionPage listVersions(String bucket, String prefix, String keyMarker,
                                              String versionMarker, int maxKeys) throws IOException {
        VersionPage page = store.listVersions(physical(bucket), prefix, keyMarker, versionMarker, maxKeys);
        return new VersionPage(page.entries().stream().map(entry -> new VersionEntry(entry.key(),
            entry.versionId(), entry.modified(), entry.deleteMarker(), entry.latest(),
            visible(entry.metadata()))).toList(), page.nextKey(), page.nextVersionId(), page.truncated());
    }

    @Override public ListPage list(String bucket, String prefix, String delimiter,
                                   int maxKeys, String after) throws IOException {
        ListPage page = store.list(physical(bucket), prefix, delimiter, maxKeys, after);
        return new ListPage(page.objects().stream().map(entry ->
            new ListedObject(entry.key(), visible(entry.metadata()))).toList(),
            page.prefixes(), page.nextKey(), page.truncated());
    }

    @Override public synchronized void ensureBucket(String bucket) throws IOException {
        if (aliases.containsValue(bucket)) return;
        store.ensureBucket(physical(bucket));
    }

    @Override public Bucket bucket(String bucket) throws IOException {
        return visible(store.bucket(physical(bucket)));
    }

    @Override public List<Bucket> buckets() throws IOException {
        return store.buckets().stream().map(this::visible).toList();
    }

    @Override public synchronized void createBucket(String bucket) throws IOException {
        if (aliases.containsKey(bucket) || aliases.containsValue(bucket))
            throw new StoreException(409, "BucketAlreadyExists", "Bucket name is already in use");
        store.createBucket(bucket);
    }

    @Override public synchronized void deleteBucket(String bucket) throws IOException {
        String name = physical(bucket);
        synchronized (uploads) {
            if (!uploads.listUploads(name, "").isEmpty())
                throw new StoreException(409, "BucketNotEmpty", "Bucket contains multipart uploads");
            if (!aliases.containsKey(bucket)) {
                store.deleteBucket(name);
                return;
            }
            catalog.beginDelete(bucket, name);
            boolean deleted = false;
            try {
                store.deleteBucket(name);
                deleted = true;
                Map<String, String> next = new HashMap<>(aliases);
                next.remove(bucket);
                save(next);
                catalog.finishDelete();
            } catch (IOException | RuntimeException error) {
                try {
                    if (deleted) aliases = catalog.load();
                    else if (error instanceof StoreException) catalog.finishDelete();
                } catch (IOException recovery) { error.addSuppressed(recovery); }
                throw error;
            }
        }
    }

    @Override public void setBucketAcl(String bucket, Map<String, String> acl) throws IOException {
        store.setBucketAcl(physical(bucket), acl);
    }

    @Override public void setBucketOwnership(String bucket, ObjectOwnership ownership) throws IOException {
        store.setBucketOwnership(physical(bucket), ownership);
    }

    @Override public void setPublicAccessBlock(String bucket, PublicAccessBlock block) throws IOException {
        store.setPublicAccessBlock(physical(bucket), block);
    }

    @Override public void setBucketPolicy(String bucket, String policy) throws IOException {
        String name = physical(bucket);
        store.setBucketPolicy(name, policyName(policy, bucket, name));
    }

    @Override public void setObjectAcl(String bucket, String key, String versionId,
                                       Map<String, String> acl) throws IOException {
        store.setObjectAcl(physical(bucket), key, versionId, acl);
    }

    @Override public Limits limits() { return store.limits(); }
    @Override public boolean ready() { return store.ready(); }
    @Override public void close() throws IOException { store.close(); }

    @Override public String create(String bucket, String key, String contentType,
                                   Map<String, String> userMetadata, Map<String, String> tags,
                                   Map<String, String> acl) throws IOException {
        return uploads.create(physical(bucket), key, contentType, userMetadata, tags, acl);
    }

    @Override public String putPart(String id, String bucket, String key, int number, InputStream input,
                                    long length, String expectedHash, String checksum) throws IOException {
        return uploads.putPart(id, physical(bucket), key, number, input, length, expectedHash, checksum);
    }

    @Override public Metadata complete(String id, String bucket, String key, List<Part> parts)
        throws IOException {
        return visible(uploads.complete(id, physical(bucket), key, parts));
    }

    @Override public void abort(String id, String bucket, String key) throws IOException {
        uploads.abort(id, physical(bucket), key);
    }

    @Override public PartPage listParts(String id, String bucket, String key, int marker, int maxParts)
        throws IOException {
        return uploads.listParts(id, physical(bucket), key, marker, maxParts);
    }

    @Override public List<UploadInfo> listUploads(String bucket, String prefix) throws IOException {
        return uploads.listUploads(physical(bucket), prefix);
    }

    @Override public int activeUploads() { return uploads.activeUploads(); }
    @Override public long stagedBytes() { return uploads.stagedBytes(); }
}
