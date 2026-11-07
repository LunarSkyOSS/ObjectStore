package cloud.lunarsky.store;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static cloud.lunarsky.store.AdminConsole.PATH;
import static cloud.lunarsky.store.AdminLayout.*;

final class AdminOperationsPages {
    private AdminOperationsPages() { }

    static String auditPage(Set<String> identities, RequestDiagnostics diagnostics, AdminAuditTrail audit, String csrf, AdminAccessCheck.Result result,
                             Map<String, String> form) throws IOException {
        StringBuilder content = new StringBuilder(consoleHeader(csrf) +
            "<div class=intro><div><h2>Audit & access</h2>" +
            "<p>Recent admin changes and effective key access</p></div></div>");
        content.append("<section class=insight><h3>Check access</h3>" +
            "<p class=muted>Checks key scope, bucket policy, identity policy, and ACL for the current object. " +
            "It does not make a request or change data.</p><form class=settings-form method=post action='")
            .append(PATH).append("/action/access-check'><input type=hidden name=csrf value='")
            .append(csrf).append("'><div class=settings-grid><label>Access key<select name=principal required>");
        for (String identity : identities.stream().sorted().toList()) {
            content.append("<option value='").append(escape(identity)).append("'")
                .append(identity.equals(form.get("principal")) ? " selected" : "")
                .append(">").append(escape(identity)).append("</option>");
        }
        content.append("</select></label><label>Operation<select name=operation>");
        for (String operation : List.of("list", "read", "write", "delete")) {
            content.append("<option value='").append(operation).append("'")
                .append(operation.equals(form.get("operation")) ? " selected" : "")
                .append(">").append(operation).append("</option>");
        }
        content.append("</select></label><label>Bucket<input name=bucket required maxlength=63 value='")
            .append(escape(form.getOrDefault("bucket", "")))
            .append("'></label><label>Object key or list prefix<input name=key maxlength=1024 value='")
            .append(escape(form.getOrDefault("key", "")))
            .append("'></label></div><button type=submit>Check access</button></form>");
        if (result != null) content.append("<div class=detail-card role=status><strong>")
            .append(result.allowed() ? "Allowed" : "Denied").append("</strong><p>")
            .append(escape(result.stage())).append(": ").append(escape(result.explanation()))
            .append("</p></div>");
        content.append("</section><section class=insight><h3>Key activity since restart</h3>" +
            "<p class=muted>Counts include authenticated S3 requests on this gateway. No secrets or object keys are logged.</p>");
        RequestDiagnostics current = diagnostics;
        List<RequestDiagnostics.KeyActivity> keys = current == null ? List.of() : current.keyActivity();
        if (keys.isEmpty()) content.append("<p class=muted>No key activity yet.</p>");
        else {
            content.append("<div class=table-wrap><table><thead><tr><th>Access key</th><th>Requests</th>" +
                "<th>Errors</th><th>Last seen (UTC)</th></tr></thead><tbody>");
            for (var key : keys) content.append("<tr><td><code>").append(escape(key.principal()))
                .append("</code></td><td>").append(key.requests()).append("</td><td>")
                .append(key.failures()).append("</td><td>")
                .append(java.time.Instant.ofEpochMilli(key.lastSeenMillis())).append("</td></tr>");
            content.append("</tbody></table></div>");
        }
        content.append("</section><section class=insight><h3>Admin event trail</h3>" +
            "<p class=muted>Last 100 events from this gateway, stored on the admin data volume. " +
            "Older events rotate out.</p>");
        List<AdminAuditTrail.Entry> entries = audit.recent(100);
        if (entries.isEmpty()) content.append("<p class=muted>No events yet.</p>");
        else {
            content.append("<div class=table-wrap><table><thead><tr><th>Time (UTC)</th><th>Action</th>" +
                "<th>Target</th><th>Result</th><th>Peer</th></tr></thead><tbody>");
            for (var entry : entries) content.append("<tr><td>")
                .append(java.time.Instant.ofEpochMilli(entry.timeMillis())).append("</td><td>")
                .append(escape(entry.action())).append("</td><td><code>")
                .append(escape(entry.target())).append("</code></td><td>")
                .append(escape(entry.result())).append("</td><td>")
                .append(escape(entry.peer())).append("</td></tr>");
            content.append("</tbody></table></div>");
        }
        return page(content.append("</section>").toString(), true);
    }

    static String jobsPage(AdminBulkJobs jobs, String csrf) {
        StringBuilder content = new StringBuilder(consoleHeader(csrf) +
            "<div class=intro><div><h2>Bulk jobs</h2><p>Copy, delete, tag, or export an object inventory</p>" +
            "</div></div><p class=perf-note>One job at a time, up to " + AdminBulkJobs.MAX_ITEMS +
            " current objects per job. Jobs continue after leaving this page, but history and reports " +
            "reset when this gateway restarts. Cancellation takes effect between objects.</p>" +
            "<section class=insight><h3>Start a job</h3><form class=settings-form method=post action='" +
            PATH + "/action/bulk-start'><input type=hidden name=csrf value='" + csrf + "'>" +
            "<div class=settings-grid><label>Operation<select name=operation>" +
            "<option value=inventory>Export inventory</option><option value=copy>Copy</option>" +
            "<option value=tag>Set tag</option><option value=delete>Delete</option></select></label>" +
            "<label>Source bucket<input name=bucket required maxlength=63></label>" +
            "<label>Key prefix<input name=prefix maxlength=1024 placeholder='Optional'></label>" +
            "<label data-bulk-only=copy>Destination bucket<input name=targetBucket maxlength=63></label>" +
            "<label data-bulk-only=copy>Destination prefix<input name=targetPrefix maxlength=1024></label>" +
            "<label data-bulk-only=tag>Tag key<input name=tag maxlength=128></label>" +
            "<label data-bulk-only=tag>Tag value<input name=value maxlength=256></label>" +
            "<label data-bulk-only=delete>Confirm source bucket<input name=confirm maxlength=63></label>" +
            "</div><p class=muted>Copy uses create-only writes and reports destination conflicts. " +
            "Delete follows bucket versioning and may create delete markers. " +
            "Inventory reports current objects only.</p><button type=submit>Start job</button>" +
            "</form></section><section class=insight><h3>Recent jobs</h3>");
        List<AdminBulkJobs.Snapshot> recent = jobs.list();
        if (recent.isEmpty()) content.append("<p class=muted>No jobs started on this gateway.</p>");
        for (AdminBulkJobs.Snapshot job : recent) {
            content.append("<div class=detail-card data-job-id='").append(job.id()).append("'>")
                .append("<div class=fact><span><strong>").append(escape(job.spec().operation()))
                .append("</strong> · <code>").append(escape(job.spec().bucket())).append("/")
                .append(escape(job.spec().prefix())).append("</code></span><biscuit-badgeview " +
                    "data-job-state theme=deep-dark>").append(escape(job.state()))
                .append("</biscuit-badgeview></div><p data-job-counts>")
                .append(job.processed()).append(" processed · ").append(job.succeeded())
                .append(" succeeded · ").append(job.failed()).append(" failed")
                .append("</p><div class=form-row><a href='").append(PATH)
                .append("/jobs.csv?id=").append(job.id()).append("'>Download CSV report</a>");
            if (job.finished() == 0) content.append("<form method=post action='").append(PATH)
                .append("/action/bulk-cancel'><input type=hidden name=csrf value='")
                .append(csrf).append("'><input type=hidden name=id value='")
                .append(job.id()).append("'><button type=submit>Cancel</button></form>");
            content.append("</div>");
            if (job.capped()) content.append("<p class=rename-warning>Stopped at the job limit. " +
                "Run another job with a narrower prefix for remaining objects.</p>");
            if (job.reportTruncated()) content.append("<p class=rename-warning>The CSV report reached its " +
                "size limit; counters include all processed objects.</p>");
            content.append("</div>");
        }
        return page(content.append("</section>").toString(), true);
    }

    static String jobJson(AdminBulkJobs.Snapshot job) {
        return "{\"state\":\"" + job.state() + "\",\"processed\":" + job.processed() +
            ",\"succeeded\":" + job.succeeded() + ",\"failed\":" + job.failed() +
            ",\"finished\":" + job.finished() + ",\"capped\":" + job.capped() +
            ",\"reportTruncated\":" + job.reportTruncated() + "}";
    }

    static String clusterPage(ObjectStorage store, String csrf) {
        StringBuilder content = new StringBuilder(consoleHeader(csrf) +
            "<div class=intro><div><h2>Cluster</h2><p>Metadata and storage-node health</p></div></div>");
        if (!(store instanceof ClusterStore cluster)) {
            content.append("<section class=insight><p class=muted>This gateway runs in disk mode. " +
                "Cluster status appears here when cluster mode is enabled.</p></section>");
            return page(content.toString(), true);
        }
        ClusterStore.ClusterStatus status = cluster.status();
        long healthy = status.nodes().stream().filter(ClusterStore.NodeStatus::reachable).count();
        long hosts = status.nodes().stream().filter(ClusterStore.NodeStatus::reachable)
            .map(node -> node.node().hostId()).distinct().count();
        content.append("<p class=perf-note>Checked ")
            .append(java.time.Instant.ofEpochMilli(status.checkedAt()))
            .append(" UTC · Refresh for a new snapshot. Checks are cached for 15 seconds and probe at most " +
                "64 nodes. Node identity probes are bounded; this page does not scan object data.</p>")
            .append("<div class=grid><biscuit-cardview><small>Metadata primary</small><strong>")
            .append(status.metadataWritable() ? "Writable" : "Unavailable or read-only")
            .append("</strong></biscuit-cardview><biscuit-cardview><small>Reachable nodes</small><strong>")
            .append(healthy).append(" / ").append(status.nodes().size())
            .append("</strong></biscuit-cardview><biscuit-cardview><small>Reachable hosts</small><strong>")
            .append(hosts).append("</strong></biscuit-cardview></div>")
            .append("<section class=cluster-topology aria-label='Cluster topology'><div class=cluster-core>")
            .append("<strong>Metadata primary</strong><p>")
            .append(status.metadataWritable() ? "Writable" : "Unavailable or read-only")
            .append("</p></div><div class=cluster-connect aria-hidden=true></div><div class=cluster-nodes>");
        for (ClusterStore.NodeStatus node : status.nodes()) {
            content.append("<div class=cluster-node data-state=")
                .append(node.reachable() ? "online" : "offline")
                .append("><strong>").append(node.reachable() ? "● Online" : "● Offline")
                .append("</strong><code>Node ").append(node.node().id())
                .append("</code><code>Host ").append(node.node().hostId())
                .append("</code><code>").append(escape(node.node().url().toString()))
                .append("</code><small>Verified replicas at last repair: ")
                .append(status.repair() == null || !status.repair().state().equals("completed") ||
                    status.repair().report().scanned() > 0 && status.verifiedReplicas().isEmpty() ?
                    "Not measured" :
                    Long.toString(status.verifiedReplicas().getOrDefault(node.node().id(), 0L)))
                .append("</small></div>");
        }
        content.append("</div></section>");
        content.append("<p class=muted>Host IDs show physical failure domains as configured by the operator. " +
            "Multiple nodes on one host do not provide host-level redundancy.</p>");
        if (status.omittedNodes() > 0) content.append("<p class=rename-warning>")
            .append(status.omittedNodes()).append(" additional nodes are not probed in this view.</p>");
        ClusterStore.RepairStatus repair = status.repair();
        content.append("<section class=insight><h3>Repair progress</h3>");
        if (repair == null) content.append("<p class=muted>No repair report has been recorded yet.</p>");
        else {
            ClusterStore.RepairReport report = repair.report();
            boolean stale = repair.state().equals("running") &&
                System.currentTimeMillis() - repair.updatedAt() > 300_000;
            content.append("<p><strong>").append(stale ? "May be interrupted" :
                repair.state().equals("running") ? "Running" :
                    repair.state().equals("failed") ? "Failed" : "Completed")
                .append("</strong> · Started ")
                .append(java.time.Instant.ofEpochMilli(repair.startedAt()))
                .append(" UTC · Last update ")
                .append(java.time.Instant.ofEpochMilli(repair.updatedAt()))
                .append(" UTC</p><p class=muted>These counts are from this repair pass, " +
                    "not a live replica inventory.</p><div class=grid>")
                .append("<biscuit-cardview><small>Segments scanned</small><strong>")
                .append(report.scanned()).append("</strong></biscuit-cardview>")
                .append("<biscuit-cardview><small>Replicas restored</small><strong>")
                .append(report.restored()).append("</strong></biscuit-cardview>")
                .append("<biscuit-cardview><small>Under-replicated</small><strong>")
                .append(report.underReplicated()).append("</strong></biscuit-cardview>")
                .append("<biscuit-cardview><small>Unrecoverable</small><strong>")
                .append(report.unrecoverable()).append("</strong></biscuit-cardview></div>");
        }
        content.append("<p class=muted>Repair is run by the separate cluster maintenance service. " +
            "This panel does not trigger repair or change placement.</p></section>");
        return page(content.toString(), true);
    }
}
