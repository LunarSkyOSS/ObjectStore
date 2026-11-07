package cloud.lunarsky.store;

public final class RequestDiagnosticsTest {
    public static void main(String[] args) {
        RequestDiagnostics diagnostics = new RequestDiagnostics();
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
        for (int index = 0; index < 70; index++)
            diagnostics.record("bounded-" + index, "Object GET", "photos", 404, 1_000_000);
        RequestDiagnostics.Snapshot bounded = diagnostics.snapshot();
        if (bounded.recentFailures().size() != 64 ||
            !bounded.recentFailures().get(0).requestId().equals("bounded-69") ||
            !bounded.recentFailures().get(63).requestId().equals("bounded-6"))
            throw new AssertionError("Recent failure history is not bounded");
        System.out.println("Request diagnostics tests passed");
    }
}
