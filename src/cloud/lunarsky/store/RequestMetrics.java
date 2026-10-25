package cloud.lunarsky.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicInteger;

final class RequestMetrics {
    private final String token;
    private final LongAdder requests = new LongAdder();
    private final LongAdder failures4xx = new LongAdder();
    private final LongAdder failures5xx = new LongAdder();
    private final LongAdder durationNanos = new LongAdder();
    private final LongAdder dataRequests = new LongAdder();
    private final LongAdder dataFailures4xx = new LongAdder();
    private final LongAdder dataFailures5xx = new LongAdder();
    private final LongAdder dataDurationNanos = new LongAdder();
    private final LongAdder inboundBytes = new LongAdder();
    private final LongAdder outboundBytes = new LongAdder();
    private final AtomicInteger dataInFlight = new AtomicInteger();

    record DataSnapshot(long requests, long failures4xx, long failures5xx,
                        long durationNanos, long inboundBytes, long outboundBytes, int inFlight) { }

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

    void dataStarted() { dataInFlight.incrementAndGet(); }

    void dataFinished(int status, long elapsedNanos, long inbound, long outbound) {
        dataRequests.increment();
        if (status >= 500) dataFailures5xx.increment();
        else if (status >= 400) dataFailures4xx.increment();
        dataDurationNanos.add(Math.max(0, elapsedNanos));
        inboundBytes.add(Math.max(0, inbound));
        outboundBytes.add(Math.max(0, outbound));
        dataInFlight.decrementAndGet();
    }

    DataSnapshot dataSnapshot() {
        return new DataSnapshot(dataRequests.sum(), dataFailures4xx.sum(),
            dataFailures5xx.sum(), dataDurationNanos.sum(), inboundBytes.sum(),
            outboundBytes.sum(), dataInFlight.get());
    }

    String render(int inFlight) {
        return "# TYPE objectstore_requests_total counter\nobjectstore_requests_total " + requests.sum() +
            "\n# TYPE objectstore_responses_4xx_total counter\nobjectstore_responses_4xx_total " + failures4xx.sum() +
            "\n# TYPE objectstore_responses_5xx_total counter\nobjectstore_responses_5xx_total " + failures5xx.sum() +
            "\n# TYPE objectstore_request_duration_seconds_total counter\nobjectstore_request_duration_seconds_total " +
            durationNanos.sum() / 1_000_000_000.0 +
            "\n# TYPE objectstore_in_flight gauge\nobjectstore_in_flight " + inFlight +
            "\n# TYPE objectstore_data_requests_total counter\nobjectstore_data_requests_total " + dataRequests.sum() +
            "\n# TYPE objectstore_ingress_bytes_total counter\nobjectstore_ingress_bytes_total " + inboundBytes.sum() +
            "\n# TYPE objectstore_egress_bytes_total counter\nobjectstore_egress_bytes_total " + outboundBytes.sum() + "\n";
    }
}
