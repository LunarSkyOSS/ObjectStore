package cloud.lunarsky.store;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import static cloud.lunarsky.store.AdminBrowser.encoded;

final class AdminBrowserView {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC);

    private AdminBrowserView() { }

    static String bucketUrl(String name, String prefix, String after) {
        StringBuilder url = new StringBuilder(AdminConsole.PATH + "/bucket?name=" + encoded(name));
        if (!prefix.isEmpty()) url.append("&prefix=").append(encoded(prefix));
        if (after != null) url.append("&after=").append(encoded(after));
        return url.toString();
    }

    static String objectUrl(String bucket, String key) {
        return AdminConsole.PATH + "/object?bucket=" + encoded(bucket) + "&amp;key=" + encoded(key);
    }

    static String downloadUrl(String bucket, String key, String version) {
        return AdminConsole.PATH + "/download?bucket=" + encoded(bucket) +
            "&amp;key=" + encoded(key) +
            (version == null ? "" : "&amp;version=" + encoded(version));
    }

    static String versionsUrl(String bucket, String prefix, String keyMarker, String versionMarker) {
        StringBuilder url = new StringBuilder(AdminConsole.PATH + "/versions?bucket=" + encoded(bucket));
        if (!prefix.isEmpty()) url.append("&prefix=").append(encoded(prefix));
        if (keyMarker != null) url.append("&keyMarker=").append(encoded(keyMarker));
        if (versionMarker != null) url.append("&versionMarker=").append(encoded(versionMarker));
        return url.toString();
    }

    static String breadcrumbs(String bucket, String prefix) {
        StringBuilder html = new StringBuilder("<nav class=crumbs aria-label=Breadcrumb><a href='")
            .append(AdminConsole.PATH).append("/buckets'>Buckets</a><span>/</span>");
        if (prefix.isEmpty()) {
            html.append("<span>").append(AdminConsole.escape(bucket)).append("</span>");
        } else {
            html.append("<a href='").append(bucketUrl(bucket, "", null)).append("'>")
                .append(AdminConsole.escape(bucket)).append("</a>");
            int from = 0;
            while (from < prefix.length()) {
                int slash = prefix.indexOf('/', from);
                if (slash < 0) break;
                String current = prefix.substring(0, slash + 1);
                String label = prefix.substring(from, slash + 1);
                html.append("<span>/</span><a href='").append(bucketUrl(bucket, current, null))
                    .append("'>").append(AdminConsole.escape(label)).append("</a>");
                from = slash + 1;
            }
        }
        return html.append("</nav>").toString();
    }

    static String parentPrefix(String prefix) {
        int last = prefix.lastIndexOf('/', prefix.length() - 2);
        return last < 0 ? "" : prefix.substring(0, last + 1);
    }

    static String attributes(String title, Map<String, String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder html = new StringBuilder("<details><summary>")
            .append(AdminConsole.escape(title)).append(" (" + values.size() + ")</summary>")
            .append("<div class=table-wrap><table><thead><tr><th>Name</th><th>Value</th></tr></thead><tbody>");
        values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
            html.append("<tr><td class=name-cell>").append(AdminConsole.escape(entry.getKey()))
                .append("</td><td class=name-cell>").append(AdminConsole.escape(entry.getValue() == null ? "" : entry.getValue()))
                .append("</td></tr>"));
        return html.append("</tbody></table></div></details>").toString();
    }

    static String fact(String name, String value) {
        return "<div class=fact><span>" + AdminConsole.escape(name) + "</span><strong>" +
            AdminConsole.escape(value) + "</strong></div>";
    }

    static String versioning(ObjectStorage.VersioningState state) {
        return switch (state) {
            case NEVER -> "Off";
            case ENABLED -> "Enabled";
            case SUSPENDED -> "Suspended";
        };
    }

    static String versioningBadge(ObjectStorage.VersioningState state) {
        String variant = state == ObjectStorage.VersioningState.ENABLED ? "success" :
            state == ObjectStorage.VersioningState.SUSPENDED ? "warning" : "accent";
        return badge(variant, "Versioning " + versioning(state));
    }

    static String accessBadge(ObjectStorage.Bucket bucket) {
        ObjectStorage.PublicAccessBlock block = bucket.publicAccessBlock();
        boolean blocked = block.blockPublicAcls() && block.ignorePublicAcls() &&
            block.blockPublicPolicy() && block.restrictPublicBuckets();
        return badge(blocked ? "success" : "warning",
            blocked ? "Public access blocked" : "Public controls relaxed");
    }

    static String badge(String variant, String text) {
        return "<biscuit-badgeview theme=deep-dark variant=" + variant + " size=small>" +
            AdminConsole.escape(text) + "</biscuit-badgeview>";
    }

    static String ownership(ObjectStorage.ObjectOwnership value) {
        return switch (value) {
            case BUCKET_OWNER_ENFORCED -> "Owner enforced";
            case BUCKET_OWNER_PREFERRED -> "Owner preferred";
            case OBJECT_WRITER -> "Object writer";
        };
    }

    static String yesNo(boolean value) {
        return value ? "On" : "Off";
    }

    static String time(long epochMillis) {
        return TIME.format(Instant.ofEpochMilli(epochMillis));
    }

}
