package cloud.lunarsky.store;

public final class RequestDiagnosticsTest {
    public static void main(String[] args) {
        RequestDiagnostics diagnostics = new RequestDiagnostics();
        rollingCounts(diagnostics);
        boundedFailures(diagnostics);
        keyCounters();
        failureLabelsAndLatency();
        System.out.println("Request diagnostics tests passed");
    }

    private static void rollingCounts(RequestDiagnostics diagnostics) {
        diagnostics.record("request-1", "Object GET", "photos", 200, 4_000_000);
        diagnostics.record("request-2", "Object PUT", "photos", 403, 16_000_000);
        diagnostics.record("request-3", "Object GET", "a".repeat(200), 503, 700_000_000);
        RequestDiagnostics.Snapshot result = diagnostics.snapshot();
        if (result.requests() != 3 || result.clientErrors() != 1 || result.serverErrors() != 1 ||
            result.p50Millis() != 25 || result.p95Millis() != 1000 || result.p99Millis() != 1000 ||
            result.recentFailures().size() != 2 ||
            !result.recentFailures().get(0).requestId().equals("request-3") ||
            !result.recentFailures().get(0).bucket().equals("—") ||
            !result.recentFailures().get(1).bucket().equals("photos"))
            throw new AssertionError("Rolling request diagnostics changed");
    }

    private static void boundedFailures(RequestDiagnostics diagnostics) {
        for (int index = 0; index < 70; index++)
            diagnostics.record("bounded-" + index, "Object GET", "photos", 404, 1_000_000);
        RequestDiagnostics.Snapshot bounded = diagnostics.snapshot();
        if (bounded.recentFailures().size() != 64 ||
            !bounded.recentFailures().get(0).requestId().equals("bounded-69") ||
            !bounded.recentFailures().get(63).requestId().equals("bounded-6"))
            throw new AssertionError("Recent failure history is not bounded");
    }

    private static void keyCounters() {
        RequestDiagnostics diagnostics = new RequestDiagnostics();
        diagnostics.record("key-ok", "Object GET", "photos", 200, 1_000_000, "reader");
        diagnostics.record("key-error", "Object GET", "photos", 403, 1_000_000, "reader");
        diagnostics.record("anonymous", "Object GET", "photos", 200, 1_000_000, null);
        diagnostics.record("invalid-key", "Object GET", "photos", 403, 1_000_000, "x".repeat(129));
        var activity = diagnostics.keyActivity();
        if (activity.size() != 1) throw new AssertionError("Invalid principals entered key counters");
        var reader = activity.getFirst();
        if (!reader.principal().equals("reader") || reader.requests() != 2 ||
            reader.failures() != 1 || reader.lastSeenMillis() <= 0)
            throw new AssertionError("Authenticated key activity changed");
    }

    private static void failureLabelsAndLatency() {
        RequestDiagnostics diagnostics = new RequestDiagnostics();
        diagnostics.record("missing-label", null, "invalid/bucket", 404, -1);
        diagnostics.record("long-label", "x".repeat(33), "photos", 503, 10_001_000_000L);
        var result = diagnostics.snapshot();
        var newest = result.recentFailures().getFirst();
        var oldest = result.recentFailures().getLast();
        if (!newest.operation().equals("Other") || !newest.bucket().equals("photos") ||
            !oldest.operation().equals("Service") || !oldest.bucket().equals("—"))
            throw new AssertionError("Failure labels were not normalized");
        if (result.p50Millis() != 1 || result.p95Millis() != -1 || result.p99Millis() != -1)
            throw new AssertionError("Latency underflow or overflow bucket changed");
    }
}
