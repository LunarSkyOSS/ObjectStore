package cloud.lunarsky.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class AdminBulkJobs {
    static final int MAX_ITEMS = 10_000;
    private static final int MAX_REPORT_CHARS = 2_000_000;
    record Spec(String operation, String bucket, String prefix, String targetBucket,
                String targetPrefix, String tag, String value) { }
    record Snapshot(String id, Spec spec, String state, int processed, int succeeded,
                    int failed, boolean capped, boolean reportTruncated, long started, long finished) { }

    static final class Job {
        final String id;
        final Spec spec;
        final long started = System.currentTimeMillis();
        final StringBuilder report = new StringBuilder("status,bucket,key,detail\n");
        volatile String state = "Running";
        volatile int processed, succeeded, failed;
        volatile boolean capped, cancel, reportTruncated;
        volatile long finished;

        Job(String id, Spec spec) {
            this.id = id;
            this.spec = spec;
        }
        Snapshot snapshot() {
            return new Snapshot(id, spec, state, processed, succeeded, failed, capped,
                reportTruncated, started, finished);
        }
    }

    private final ObjectStorage store;
    private final java.util.function.Consumer<Snapshot> onFinish;
    private final SecureRandom random = new SecureRandom();
    private final ArrayDeque<Job> history = new ArrayDeque<>();
    private volatile StoreOperations operations = new StoreOperations();
    private Job active;

    AdminBulkJobs(ObjectStorage store) { this(store, ignored -> { }); }

    AdminBulkJobs(ObjectStorage store, java.util.function.Consumer<Snapshot> onFinish) {
        this.store = store;
        this.onFinish = onFinish;
    }

    void setOperations(StoreOperations operations) { this.operations = operations; }

    synchronized boolean hasActiveJob() { return active != null && active.finished == 0; }

    Snapshot start(Spec spec) throws IOException {
        return operations.readValue(() -> startJob(spec));
    }

    private synchronized Snapshot startJob(Spec spec) throws IOException {
        if (hasActiveJob())
            throw new StoreException(409, "JobInProgress", "Wait for or cancel the current job");
        validate(spec);
        byte[] id = new byte[12];
        random.nextBytes(id);
        Job job = new Job(Base64.getUrlEncoder().withoutPadding().encodeToString(id), spec);
        active = job;
        history.addFirst(job);
        while (history.size() > 10) history.removeLast();
        Thread.ofVirtual().name("objectstore-admin-job").start(() -> run(job));
        return job.snapshot();
    }

    synchronized List<Snapshot> list() {
        return history.stream().map(Job::snapshot).toList();
    }

    synchronized Snapshot get(String id) {
        Job job = find(id);
        return job.snapshot();
    }

    synchronized String report(String id) {
        Job job = find(id);
        synchronized (job.report) { return job.report.toString(); }
    }

    synchronized void cancel(String id) {
        Job job = find(id);
        if (job.finished == 0) job.cancel = true;
    }

    private Job find(String id) {
        return history.stream().filter(job -> job.id.equals(id)).findFirst()
            .orElseThrow(() -> new StoreException(404, "NoSuchJob", "Job not found"));
    }

    private void validate(Spec spec) throws IOException {
        if (!List.of("inventory", "copy", "delete", "tag").contains(spec.operation()))
            throw new StoreException(400, "InvalidArgument", "Choose a supported bulk operation");
        store.bucket(spec.bucket());
        if (spec.prefix() == null || spec.prefix().getBytes(StandardCharsets.UTF_8).length > 1024)
            throw new StoreException(400, "InvalidArgument", "Invalid prefix");
        if (spec.operation().equals("copy")) {
            store.bucket(spec.targetBucket());
            if (spec.bucket().equals(spec.targetBucket()))
                throw new StoreException(400, "InvalidArgument", "Bulk copy requires a different destination bucket");
            if (spec.targetPrefix() == null || spec.targetPrefix().getBytes(StandardCharsets.UTF_8).length > 1024)
                throw new StoreException(400, "InvalidArgument", "Invalid destination prefix");
        }
        if (spec.operation().equals("tag") && (spec.tag() == null || spec.tag().isBlank() ||
                spec.tag().length() > 128 || spec.value() == null || spec.value().length() > 256))
            throw new StoreException(400, "InvalidArgument", "Invalid tag key or value");
        if (spec.operation().equals("tag"))
            ObjectAttributes.validateTags(Map.of(spec.tag(), spec.value()));
    }

    private void run(Job job) {
        try {
            String after = null;
            do {
                if (job.cancel) break;
                ObjectStorage.ListPage page = list(job, after);
                int remaining = MAX_ITEMS - job.processed;
                for (ObjectStorage.ListedObject entry : page.objects()) {
                    if (job.cancel || job.processed >= MAX_ITEMS) break;
                    try {
                        operations.read(() -> process(job.spec, entry));
                        job.succeeded++;
                        String detail = job.spec.operation().equals("inventory") ?
                            "bytes=" + entry.metadata().length() + " etag=" + entry.metadata().etag() :
                            job.spec.operation();
                        append(job, "ok", entry.key(), detail);
                    } catch (Exception error) {
                        job.failed++;
                        String detail = error instanceof StoreException storeError ?
                            storeError.code + ": " + storeError.getMessage() :
                            error.getClass().getSimpleName();
                        append(job, "error", entry.key(), detail.length() > 200 ?
                            detail.substring(0, 200) : detail);
                    }
                    job.processed++;
                }
                if (job.processed >= MAX_ITEMS) {
                    job.capped = page.truncated() || page.objects().size() > remaining;
                    break;
                }
                if (!page.truncated()) break;
                if (page.nextKey() == null || page.nextKey().equals(after))
                    throw new IOException("Storage listing did not advance");
                after = page.nextKey();
            } while (true);
            job.state = job.cancel ? "Cancelled" : job.capped ? "Capped" : "Completed";
        } catch (Exception error) {
            job.state = "Failed";
            append(job, "error", "", error.getClass().getSimpleName());
        } finally {
            job.finished = System.currentTimeMillis();
            onFinish.accept(job.snapshot());
        }
    }

    private ObjectStorage.ListPage list(Job job, String after) throws IOException {
        return operations.readValue(() ->
            store.list(job.spec.bucket(), job.spec.prefix(), "", 100, after));
    }

    private void process(Spec spec, ObjectStorage.ListedObject entry) throws IOException {
        String key = entry.key();
        switch (spec.operation()) {
            case "inventory" -> { }
            case "delete" -> store.delete(spec.bucket(), key);
            case "tag" -> {
                Map<String, String> tags = new HashMap<>(store.tags(spec.bucket(), key));
                tags.put(spec.tag(), spec.value());
                ObjectAttributes.validateTags(tags);
                store.setTags(spec.bucket(), key, Map.copyOf(tags));
            }
            case "copy" -> {
                try (ObjectStorage.OpenObject source = store.open(spec.bucket(), key)) {
                    ObjectStorage.Metadata meta = source.metadata();
                    String suffix = key.substring(spec.prefix().length());
                    store.put(spec.targetBucket(), spec.targetPrefix() + suffix, source.stream(),
                        meta.length(), SigV4.hex(meta.sha256()), null, true, meta.contentType(),
                        meta.userMetadata(), meta.tags(), meta::checksums, Map.of());
                }
            }
            default -> throw new IllegalStateException();
        }
    }

    private static void append(Job job, String status, String key, String detail) {
        synchronized (job.report) {
            if (job.report.length() >= MAX_REPORT_CHARS) {
                if (!job.reportTruncated) job.report.append("report-truncated,,,See job counters for totals\n");
                job.reportTruncated = true;
                return;
            }
            job.report.append(csv(status)).append(',').append(csv(job.spec.bucket())).append(',')
                .append(csv(key)).append(',').append(csv(detail)).append('\n');
        }
    }

    private static String csv(String value) {
        String safe = value.replace("\r", " ").replace("\n", " ").replace("\t", " ");
        if (!safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
