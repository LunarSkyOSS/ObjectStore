package cloud.lunarsky.store;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

public final class AdminBulkJobsTest {
    private static final String BUCKET = "test-bucket";
    private static final ObjectStorage.Metadata METADATA = new ObjectStorage.Metadata(
        4, 0, "etag", new byte[32], BUCKET, "", "text/plain");

    @FunctionalInterface
    private interface Listing {
        ObjectStorage.ListPage get(int count, String after) throws Exception;
    }

    @FunctionalInterface
    private interface Deletion {
        void delete(String key) throws Exception;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static ObjectStorage storage(Listing listing, Deletion deletion) {
        return (ObjectStorage) Proxy.newProxyInstance(ObjectStorage.class.getClassLoader(),
            new Class<?>[]{ObjectStorage.class}, (proxy, method, arguments) -> switch (method.getName()) {
                case "bucket" -> new ObjectStorage.Bucket(BUCKET, 0);
                case "list" -> listing.get((int) arguments[3], (String) arguments[4]);
                case "delete" -> {
                    deletion.delete((String) arguments[1]);
                    yield null;
                }
                default -> throw new AssertionError("Unexpected storage operation: " + method.getName());
            });
    }

    private static ObjectStorage.ListPage page(int start, int end, String next, boolean truncated) {
        List<ObjectStorage.ListedObject> objects = IntStream.range(start, end)
            .mapToObj(index -> new ObjectStorage.ListedObject(Integer.toString(index), METADATA)).toList();
        return new ObjectStorage.ListPage(objects, List.of(), next, truncated);
    }

    private static AdminBulkJobs.Spec spec(String operation) {
        return new AdminBulkJobs.Spec(operation, BUCKET, "", "", "", "", "");
    }

    private static AdminBulkJobs.Snapshot finished(ArrayBlockingQueue<AdminBulkJobs.Snapshot> results)
            throws InterruptedException {
        AdminBulkJobs.Snapshot result = results.poll(10, TimeUnit.SECONDS);
        if (result == null) throw new AssertionError("Bulk job did not finish");
        return result;
    }

    private static void itemLimit(int total, boolean oversizedPage) throws Exception {
        ObjectStorage store = storage((count, after) -> {
            int start = after == null ? 0 : Integer.parseInt(after) + 1;
            int end = oversizedPage ? total : Math.min(start + count, total);
            return page(start, end, Integer.toString(end - 1), end < total);
        }, key -> { });
        var results = new ArrayBlockingQueue<AdminBulkJobs.Snapshot>(2);
        AdminBulkJobs jobs = new AdminBulkJobs(store, results::add);
        jobs.start(spec("inventory"));
        AdminBulkJobs.Snapshot result = finished(results);
        boolean capped = total > AdminBulkJobs.MAX_ITEMS;
        check(result.capped() == capped, "Incorrect cap at the item boundary");
        check(result.state().equals(capped ? "Capped" : "Completed"), "Incorrect completion state");
        check(result.processed() == AdminBulkJobs.MAX_ITEMS && result.succeeded() == result.processed(),
            "Item limit lost or overran entries");
        check(!jobs.hasActiveJob(), "Finished job is still active");
    }

    private static void cancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger deletes = new AtomicInteger();
        ObjectStorage store = storage((count, after) -> page(0, 3, null, false), key -> {
            deletes.incrementAndGet();
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Delete was not released");
        });
        var results = new ArrayBlockingQueue<AdminBulkJobs.Snapshot>(2);
        AdminBulkJobs jobs = new AdminBulkJobs(store, results::add);
        AdminBulkJobs.Snapshot started = jobs.start(spec("delete"));
        try {
            check(entered.await(10, TimeUnit.SECONDS), "Delete did not start");
            jobs.cancel(started.id());
        } finally {
            release.countDown();
        }
        AdminBulkJobs.Snapshot result = finished(results);
        check(result.state().equals("Cancelled") && result.processed() == 1 && result.succeeded() == 1,
            "Cancellation did not finish the in-progress entry");
        check(deletes.get() == 1, "Cancellation started another entry");
    }

    private static void stalledPagination() throws Exception {
        ObjectStorage store = storage((count, after) -> page(0, 1, "0", true), key -> { });
        var results = new ArrayBlockingQueue<AdminBulkJobs.Snapshot>(2);
        AdminBulkJobs jobs = new AdminBulkJobs(store, results::add);
        jobs.start(spec("inventory"));
        AdminBulkJobs.Snapshot result = finished(results);
        check(result.state().equals("Failed") && result.processed() == 2,
            "A repeated pagination marker did not stop the job");
        check(jobs.report(result.id()).contains("IOException"), "Listing failure missing from report");
    }

    private static void entryFailure() throws Exception {
        ObjectStorage store = storage((count, after) -> page(0, 3, null, false), key -> {
            if (key.equals("1")) throw new StoreException(403, "AccessDenied", "x".repeat(300));
        });
        var results = new ArrayBlockingQueue<AdminBulkJobs.Snapshot>(2);
        AdminBulkJobs jobs = new AdminBulkJobs(store, results::add);
        jobs.start(spec("delete"));
        AdminBulkJobs.Snapshot result = finished(results);
        check(result.state().equals("Completed") && result.processed() == 3 &&
            result.succeeded() == 2 && result.failed() == 1, "Entry failure aborted or miscounted the job");
        String report = jobs.report(result.id());
        check(report.contains("AccessDenied: ") && !report.contains("x".repeat(201)),
            "Entry failure detail was missing or unbounded");
    }

    public static void main(String[] args) throws Exception {
        itemLimit(AdminBulkJobs.MAX_ITEMS, false);
        itemLimit(AdminBulkJobs.MAX_ITEMS + 1, false);
        itemLimit(AdminBulkJobs.MAX_ITEMS + 1, true);
        cancellation();
        stalledPagination();
        entryFailure();
        System.out.println("Admin bulk job tests passed: cap, cancellation, pagination, errors");
    }
}
