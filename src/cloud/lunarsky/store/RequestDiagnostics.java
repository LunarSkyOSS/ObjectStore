package cloud.lunarsky.store;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.LongAdder;

final class RequestDiagnostics {
    private static final long MINUTE_MILLIS = 60_000;
    private static final long[] LATENCY_BOUNDS_MS = {1, 2, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000, 10000};
    private final AtomicReferenceArray<Window> windows = new AtomicReferenceArray<>(5);
    private final AtomicReferenceArray<Failure> failures = new AtomicReferenceArray<>(64);
    private final AtomicLong failureSequence = new AtomicLong();
    private final ConcurrentHashMap<String, KeyCounters> keys = new ConcurrentHashMap<>();

    record Failure(long sequence, long timeMillis, String requestId, String operation, String bucket,
                   int status) { }
    record Snapshot(long requests, long clientErrors, long serverErrors, long p50Millis,
                    long p95Millis, long p99Millis, List<Failure> recentFailures) { }
    record KeyActivity(String principal, long requests, long failures, long lastSeenMillis) { }

    private static final class KeyCounters {
        final LongAdder requests = new LongAdder();
        final LongAdder failures = new LongAdder();
        final AtomicLong lastSeen = new AtomicLong();
    }

    private static final class Window {
        final long minute;
        final LongAdder requests = new LongAdder();
        final LongAdder clientErrors = new LongAdder();
        final LongAdder serverErrors = new LongAdder();
        final LongAdder[] latency = new LongAdder[LATENCY_BOUNDS_MS.length + 1];

        Window(long minute) {
            this.minute = minute;
            for (int i = 0; i < latency.length; i++) latency[i] = new LongAdder();
        }
    }

    void record(String requestId, String operation, String bucket, int status, long elapsedNanos) {
        record(requestId, operation, bucket, status, elapsedNanos, null);
    }

    void record(String requestId, String operation, String bucket, int status, long elapsedNanos,
                String principal) {
        if (operation == null) operation = "Service";
        long now = System.currentTimeMillis();
        long minute = now / MINUTE_MILLIS;
        int slot = (int) Math.floorMod(minute, windows.length());
        Window window;
        do {
            window = windows.get(slot);
            if (window != null && window.minute == minute) break;
            Window fresh = new Window(minute);
            if (windows.compareAndSet(slot, window, fresh)) {
                window = fresh;
                break;
            }
        } while (true);
        window.requests.increment();
        if (status >= 500) window.serverErrors.increment();
        else if (status >= 400) window.clientErrors.increment();
        long millis = Math.max(0, elapsedNanos / 1_000_000);
        int latencySlot = 0;
        while (latencySlot < LATENCY_BOUNDS_MS.length && millis > LATENCY_BOUNDS_MS[latencySlot])
            latencySlot++;
        window.latency[latencySlot].increment();
        if (principal != null && principal.length() <= 128) {
            KeyCounters counters = keys.get(principal);
            if (counters == null && keys.size() < 128)
                counters = keys.computeIfAbsent(principal, ignored -> new KeyCounters());
            if (counters != null) {
                counters.requests.increment();
                if (status >= 400) counters.failures.increment();
                counters.lastSeen.set(now);
            }
        }
        if (status >= 400) {
            if (operation.length() > 32) operation = "Other";
            if (bucket == null || bucket.length() > 63 || !bucket.matches("[a-zA-Z0-9.-]+")) bucket = "—";
            long sequence = failureSequence.getAndIncrement();
            failures.set((int) (sequence % failures.length()),
                new Failure(sequence, now, requestId, operation, bucket, status));
        }
    }

    List<KeyActivity> keyActivity() {
        return keys.entrySet().stream().map(entry -> new KeyActivity(entry.getKey(),
                entry.getValue().requests.sum(), entry.getValue().failures.sum(),
                entry.getValue().lastSeen.get()))
            .sorted((a, b) -> Long.compare(b.lastSeenMillis(), a.lastSeenMillis()))
            .limit(100).toList();
    }

    Snapshot snapshot() {
        long minute = System.currentTimeMillis() / MINUTE_MILLIS;
        long requests = 0, clientErrors = 0, serverErrors = 0;
        long[] histogram = new long[LATENCY_BOUNDS_MS.length + 1];
        for (int slot = 0; slot < windows.length(); slot++) {
            Window window = windows.get(slot);
            if (window == null || window.minute > minute || window.minute <= minute - windows.length()) continue;
            requests += window.requests.sum();
            clientErrors += window.clientErrors.sum();
            serverErrors += window.serverErrors.sum();
            for (int i = 0; i < histogram.length; i++) histogram[i] += window.latency[i].sum();
        }
        List<Failure> recent = new ArrayList<>();
        long newest = failureSequence.get();
        for (long sequence = newest - 1; sequence >= Math.max(0, newest - failures.length()); sequence--) {
            Failure failure = failures.get((int) (sequence % failures.length()));
            if (failure != null && failure.sequence() == sequence &&
                failure.timeMillis() >= (minute - windows.length() + 1) * MINUTE_MILLIS &&
                failure.timeMillis() <= System.currentTimeMillis()) recent.add(failure);
        }
        return new Snapshot(requests, clientErrors, serverErrors,
            percentile(histogram, requests, 50), percentile(histogram, requests, 95),
            percentile(histogram, requests, 99), List.copyOf(recent));
    }

    private static long percentile(long[] histogram, long count, int percent) {
        if (count == 0) return 0;
        long target = (count * percent + 99) / 100;
        long seen = 0;
        for (int i = 0; i < histogram.length; i++) {
            seen += histogram[i];
            if (seen >= target) return i == LATENCY_BOUNDS_MS.length ? -1 : LATENCY_BOUNDS_MS[i];
        }
        return -1;
    }
}
