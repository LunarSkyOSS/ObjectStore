package cloud.lunarsky.store;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

final class AdminPerformance {
    private static final MemoryMXBean MEMORY = ManagementFactory.getMemoryMXBean();
    private static final List<GarbageCollectorMXBean> COLLECTORS =
        ManagementFactory.getGarbageCollectorMXBeans();
    private static final ProcessHandle PROCESS = ProcessHandle.current();
    private static final Path STATUS = Path.of("/proc/self/status");

    private AdminPerformance() { }

    static String snapshot(RequestMetrics metrics) {
        RequestMetrics.DataSnapshot traffic = metrics.dataSnapshot();
        long gcCount = 0;
        long gcMillis = 0;
        for (GarbageCollectorMXBean collector : COLLECTORS) {
            gcCount += Math.max(0, collector.getCollectionCount());
            gcMillis += Math.max(0, collector.getCollectionTime());
        }
        long cpuNanos = PROCESS.info().totalCpuDuration().map(Duration::toNanos).orElse(-1L);
        return "{\"requests\":" + traffic.requests() +
            ",\"failures4xx\":" + traffic.failures4xx() +
            ",\"failures5xx\":" + traffic.failures5xx() +
            ",\"durationNanos\":" + traffic.durationNanos() +
            ",\"inboundBytes\":" + traffic.inboundBytes() +
            ",\"outboundBytes\":" + traffic.outboundBytes() +
            ",\"inFlight\":" + traffic.inFlight() +
            ",\"cpuNanos\":" + cpuNanos +
            ",\"processors\":" + Runtime.getRuntime().availableProcessors() +
            ",\"rssBytes\":" + rssBytes() +
            ",\"heapBytes\":" + MEMORY.getHeapMemoryUsage().getUsed() +
            ",\"nonHeapBytes\":" + MEMORY.getNonHeapMemoryUsage().getUsed() +
            ",\"gcCount\":" + gcCount +
            ",\"gcMillis\":" + gcMillis + "}";
    }

    private static long rssBytes() {
        if (!Files.isReadable(STATUS)) return -1;
        try (var lines = Files.lines(STATUS)) {
            String line = lines.filter(value -> value.startsWith("VmRSS:"))
                .findFirst().orElse("");
            if (line.isEmpty()) return -1;
            String value = line.substring("VmRSS:".length()).trim();
            return Math.multiplyExact(Long.parseLong(value.split("\\s+", 2)[0]), 1024);
        } catch (IOException | NumberFormatException | ArithmeticException unavailable) {
            return -1;
        }
    }
}
