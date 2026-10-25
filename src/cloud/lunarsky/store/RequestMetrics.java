package cloud.lunarsky.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.LongAdder;

final class RequestMetrics {
    private final String token;
    private final LongAdder requests = new LongAdder();
    private final LongAdder failures4xx = new LongAdder();
    private final LongAdder failures5xx = new LongAdder();
    private final LongAdder durationNanos = new LongAdder();

    RequestMetrics(String token) {
        if (token != null && !token.isBlank() && token.length() < 32)
            throw new IllegalArgumentException("METRICS_BEARER_TOKEN must have at least 32 characters");
        this.token = token == null || token.isBlank() ? null : token;
    }

    boolean enabled() { return token != null; }

    boolean authorized(String authorization) {
        if (token == null || authorization == null || !authorization.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
            authorization.substring(7).getBytes(StandardCharsets.UTF_8));
    }

    void record(int status, long elapsedNanos) {
        requests.increment();
        if (status >= 500) failures5xx.increment();
        else if (status >= 400) failures4xx.increment();
        durationNanos.add(Math.max(0, elapsedNanos));
    }

    String render(int inFlight) {
        return "# TYPE objectstore_requests_total counter\nobjectstore_requests_total " + requests.sum() +
            "\n# TYPE objectstore_responses_4xx_total counter\nobjectstore_responses_4xx_total " + failures4xx.sum() +
            "\n# TYPE objectstore_responses_5xx_total counter\nobjectstore_responses_5xx_total " + failures5xx.sum() +
            "\n# TYPE objectstore_request_duration_seconds_total counter\nobjectstore_request_duration_seconds_total " +
            durationNanos.sum() / 1_000_000_000.0 +
            "\n# TYPE objectstore_in_flight gauge\nobjectstore_in_flight " + inFlight + "\n";
    }
}
