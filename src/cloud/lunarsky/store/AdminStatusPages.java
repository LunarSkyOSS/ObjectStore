package cloud.lunarsky.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileStore;
import java.util.List;
import static cloud.lunarsky.store.AdminConsole.PATH;
import static cloud.lunarsky.store.AdminLayout.*;

final class AdminStatusPages {
    private AdminStatusPages() { }

    static String overviewPage(String mode, String status, long maxObject, long maxTotal,
                                       List<ObjectStorage.Bucket> buckets, boolean bucketListAvailable,
                                       String csrf) {
        StringBuilder content = new StringBuilder(consoleHeader(csrf) +
            "<div class=intro><div><h2>Overview</h2><p>Service status and configured limits</p></div>" +
            "<biscuit-badgeview theme=deep-dark variant='" + (status.equals("Ready") ? "success" : "danger") + "'>" +
            status + "</biscuit-badgeview></div>" +
            "<div class=grid><biscuit-cardview><small>Storage mode</small><strong>" + mode +
            "</strong></biscuit-cardview><biscuit-cardview><small>Buckets</small><strong>" +
            (bucketListAvailable ? buckets.size() : "Unavailable") +
            "</strong></biscuit-cardview><biscuit-cardview><small>Maximum object</small><strong>" +
            formatBytes(maxObject) + "</strong></biscuit-cardview><biscuit-cardview>" +
            "<small>Total storage limit</small><strong>" + formatBytes(maxTotal) +
            "</strong></biscuit-cardview></div><section class=buckets><h2>Buckets</h2>" +
            "<p>First 25 buckets. <a href='" + PATH +
            "/buckets'>Browse all buckets</a></p>");
        if (!bucketListAvailable || buckets.isEmpty()) {
            content.append("<div class='table-wrap empty'>")
                .append(bucketListAvailable ? "No buckets yet." : "Bucket list unavailable.")
                .append("</div>");
        } else {
            content.append("<div class=table-wrap><table><thead><tr><th>Bucket</th><th>Versioning</th>" +
                "<th>Ownership</th></tr></thead><tbody>");
            for (ObjectStorage.Bucket bucket : buckets.stream().limit(25).toList()) {
                String versioning = bucket.versioning() == ObjectStorage.VersioningState.NEVER ?
                    "Off" : bucket.versioning().name().toLowerCase(java.util.Locale.ROOT);
                String ownership = switch (bucket.ownership()) {
                    case BUCKET_OWNER_ENFORCED -> "Owner enforced";
                    case BUCKET_OWNER_PREFERRED -> "Owner preferred";
                    case OBJECT_WRITER -> "Object writer";
                };
                content.append("<tr><td><a href='").append(AdminBrowser.bucketUrl(bucket.name(), "", null))
                    .append("'>").append(escape(bucket.name())).append("</a></td><td>")
                    .append(versioning).append("</td><td>").append(ownership).append("</td></tr>");
            }
            content.append("</tbody></table></div>");
        }
        content.append("</section>");
        return page(content.toString(), true);
    }

    static String performancePage(String csrf) {
        StringBuilder content = new StringBuilder(consoleHeader(csrf));
        content.append("<div class=intro><div><h2>Performance</h2>")
            .append("<p>Live S3 traffic and process health</p></div>")
            .append("<biscuit-badgeview theme=deep-dark variant=accent>Live</biscuit-badgeview></div>")
            .append("<p class=perf-note>Live samples arrive about once a second while this tab is visible. ")
            .append("Traffic excludes admin pages and health checks. Charts keep only the last 60 samples ")
            .append("in this browser tab.</p><div class=perf-status-row>")
            .append("<biscuit-loadingindicator id=perf-loader theme=deep-dark size=small ")
            .append("label='Loading performance metrics' aria-hidden=true></biscuit-loadingindicator>")
            .append("<p id=perf-state class=perf-status data-state=loading role=status>Collecting samples…</p></div>")
            .append("<div class=perf-grid>")
            .append(perfCard("Requests / second", "perf-rps"))
            .append(perfCard("Average latency", "perf-latency"))
            .append(perfCard("Inbound / second", "perf-inbound"))
            .append(perfCard("Outbound / second", "perf-outbound"))
            .append(perfCard("Average CPU · recent", "perf-cpu"))
            .append(perfCard("Process RAM (RSS)", "perf-ram"))
            .append(perfCard("GC collections", "perf-gc"))
            .append(perfCard("In flight", "perf-inflight"))
            .append("</div><div class=perf-charts hidden>")
            .append("<biscuit-chartview id=perf-requests-chart theme=deep-dark type=line ")
            .append("name='Requests / second' height=250 max-rows=60></biscuit-chartview>")
            .append("<biscuit-chartview id=perf-transfer-chart theme=deep-dark type=line ")
            .append("name='Transfer / second' height=250 max-rows=60></biscuit-chartview>")
            .append("<biscuit-chartview id=perf-cpu-chart theme=deep-dark type=line ")
            .append("name='Process CPU' height=250 max-rows=60></biscuit-chartview>")
            .append("<biscuit-chartview id=perf-memory-chart theme=deep-dark type=line ")
            .append("name='Process memory' height=250 max-rows=60></biscuit-chartview>")
            .append("</div><p class=perf-note>Inbound and outbound measure HTTP body bytes, including ")
            .append("S3 metadata responses. CPU is the process share of available cores. ")
            .append("GC counts reset when the service restarts.</p>");
        return page(content.toString(), true, true);
    }

    static String healthPage(DiskStore diskStore, ObjectStorage store, String csrf) {
        boolean ready = store.ready();
        String mode = diskStore == null ? "Cluster" : "Disk";
        StringBuilder content = new StringBuilder(consoleHeader(csrf) +
            "<div class=intro><div><h2>Health & recovery</h2>" +
            "<p>Current service and storage state</p></div><biscuit-badgeview variant='" +
            (ready ? "success" : "danger") + "'>" + (ready ? "Ready" : "Unavailable") +
            "</biscuit-badgeview></div><div class=grid>" +
            "<biscuit-cardview><small>Storage mode</small><strong>" + mode +
            "</strong></biscuit-cardview>");
        if (diskStore != null) {
            long used = diskStore.usedBytes();
            long quota = store.limits().maxTotalBytes();
            content.append("<biscuit-cardview><small>Quota used</small><strong>")
                .append(formatBytes(used)).append(" / ").append(formatBytes(quota))
                .append("</strong></biscuit-cardview><biscuit-cardview><small>Current objects</small><strong>")
                .append(diskStore.objectCount()).append("</strong></biscuit-cardview>");
            try {
                FileStore volume = Files.getFileStore(diskStore.root());
                long free = volume.getUsableSpace();
                content.append("<biscuit-cardview><small>Filesystem available</small><strong>")
                    .append(formatBytes(free)).append("</strong></biscuit-cardview>");
                if (free < Math.max(0, quota - used))
                    content.append("<p class=rename-warning>Filesystem free space is below the remaining configured quota. " +
                        "The volume can fill before the quota is reached.</p>");
            } catch (IOException unavailable) {
                content.append("<biscuit-cardview><small>Filesystem available</small><strong>Unavailable" +
                    "</strong></biscuit-cardview>");
            }
        } else {
            content.append("<biscuit-cardview><small>Storage nodes</small><strong>Not measured here" +
                "</strong></biscuit-cardview>");
        }
        content.append("</div><section class=insight><div class=detail-card><h3>Recovery readiness</h3>" +
            "<div class=fact><span>Latest off-site backup</span><strong>Not reported</strong></div>" +
            "<div class=fact><span>Latest restore test</span><strong>Not reported</strong></div>" +
            "<p class=muted>ObjectStore cannot verify external backups or restores. Check your backup jobs " +
            "and perform a restore drill before relying on this service for important data.</p>" +
            "<a href='https://lunarsky.cloud/objectstore/v0.0.8/operations'>Recovery documentation</a>" +
            "</div></section>");
        return page(content.toString(), true);
    }

    static String diagnosticsPage(RequestDiagnostics diagnostics, String csrf) {
        RequestDiagnostics current = diagnostics;
        RequestDiagnostics.Snapshot snapshot = current == null ? null : current.snapshot();
        StringBuilder content = new StringBuilder(consoleHeader(csrf) +
            "<div class=intro><div><h2>Request diagnostics</h2>" +
            "<p>Recent S3 requests on this gateway</p></div></div>" +
            "<p class=perf-note>Five-minute rolling window. Latency percentiles are histogram upper bounds. " +
            "Admin requests and health checks are excluded. Refresh to update.</p>");
        if (snapshot == null) {
            content.append("<p class=error>Request diagnostics are unavailable.</p>");
        } else {
            content.append("<div class=grid><biscuit-cardview><small>Requests</small><strong>")
                .append(snapshot.requests()).append("</strong></biscuit-cardview>")
                .append("<biscuit-cardview><small>4xx / 5xx</small><strong>")
                .append(snapshot.clientErrors()).append(" / ").append(snapshot.serverErrors())
                .append("</strong></biscuit-cardview>")
                .append("<biscuit-cardview><small>p50 / p95</small><strong>")
                .append(latencyLabel(snapshot.p50Millis())).append(" / ")
                .append(latencyLabel(snapshot.p95Millis())).append("</strong></biscuit-cardview>")
                .append("<biscuit-cardview><small>p99</small><strong>")
                .append(latencyLabel(snapshot.p99Millis())).append("</strong></biscuit-cardview></div>")
                .append("<section class=insight><h3>Recent errors</h3><p class=muted>Most recent 64 errors; " +
                    "bucket names and request IDs only. Object keys and credentials are not retained.</p>");
            if (snapshot.recentFailures().isEmpty()) {
                content.append("<div class='table-wrap empty'>No errors in this window.</div>");
            } else {
                content.append("<div class=table-wrap><table><thead><tr><th>Time (UTC)</th><th>Status</th>" +
                    "<th>Operation</th><th>Bucket</th><th>Request ID</th></tr></thead><tbody>");
                for (RequestDiagnostics.Failure failure : snapshot.recentFailures()) {
                    content.append("<tr><td>").append(java.time.Instant.ofEpochMilli(failure.timeMillis()))
                        .append("</td><td>").append(failure.status()).append("</td><td>")
                        .append(escape(failure.operation())).append("</td><td>")
                        .append(escape(failure.bucket())).append("</td><td><code>")
                        .append(escape(failure.requestId())).append("</code></td></tr>");
                }
                content.append("</tbody></table></div>");
            }
            content.append("</section>");
        }
        content.append("<section class=insight><div class=detail-card><h3>Connection probe</h3>" +
            "<p class=muted>Download 1 MiB from the admin listener to measure browser-to-gateway timing. " +
            "This does not measure object reads, disks, or storage nodes.</p>" +
            "<button id=diagnostic-probe type=button>Run probe</button> " +
            "<span id=diagnostic-probe-result role=status></span></div></section>");
        return page(content.toString(), true);
    }

    static String latencyLabel(long upperMillis) {
        return upperMillis < 0 ? ">10 s" : upperMillis + " ms";
    }

    static String perfCard(String label, String id) {
        return "<biscuit-cardview><small>" + label + "</small><strong id='" + id +
            "'><biscuit-skeletonview width=72px height=22px></biscuit-skeletonview>" +
            "</strong></biscuit-cardview>";
    }
}
