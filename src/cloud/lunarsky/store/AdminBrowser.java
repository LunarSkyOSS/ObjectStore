package cloud.lunarsky.store;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class AdminBrowser {
    private static final int PAGE_SIZE = 50;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC);

    private AdminBrowser() { }

    static String index(ObjectStorage store, String rawQuery, String csrf) throws IOException {
        Map<String, String> query = query(rawQuery, Set.of("filter", "after"));
        String filter = query.getOrDefault("filter", "").trim();
        String after = query.get("after");
        if (filter.length() > 256 || after != null && after.length() > 255)
            throw invalidQuery();
        List<ObjectStorage.Bucket> matches = store.buckets().stream()
            .filter(bucket -> bucket.name().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))
            .sorted(Comparator.comparing(ObjectStorage.Bucket::name)).toList();
        List<ObjectStorage.Bucket> page = matches.stream()
            .filter(bucket -> after == null || bucket.name().compareTo(after) > 0)
            .limit(PAGE_SIZE + 1).toList();
        boolean next = page.size() > PAGE_SIZE;
        if (next) page = page.subList(0, PAGE_SIZE);

        StringBuilder html = new StringBuilder(AdminConsole.consoleHeader(csrf));
        html.append("<nav class=crumbs aria-label=Breadcrumb><a href='")
            .append(AdminConsole.PATH).append("/overview'>Overview</a><span>/</span><span>Buckets</span></nav>")
            .append("<div class=intro><div><h2>Buckets</h2><p>")
            .append(matches.size()).append(" matching buckets · read-only</p></div></div>")
            .append("<div class=toolbar><form method=get action='").append(AdminConsole.PATH)
            .append("/buckets'><div class=field><label for=bucket-filter>Find a bucket</label>")
            .append("<input id=bucket-filter name=filter value='").append(AdminConsole.escape(filter))
            .append("' placeholder='Bucket name'></div><button type=submit>Search</button></form>")
            .append("<a class=button-link href='").append(AdminConsole.PATH)
            .append("/overview'>Overview</a></div>");
        if (page.isEmpty()) {
            html.append("<div class='table-wrap empty'>No buckets match this search.</div>");
        } else {
            html.append("<div class=table-wrap><table><thead><tr><th>Bucket</th><th>Versioning</th>")
                .append("<th>Ownership</th><th>Created</th></tr></thead><tbody>");
            for (ObjectStorage.Bucket bucket : page) {
                html.append("<tr><td class=name-cell><a href='")
                    .append(bucketUrl(bucket.name(), "", null)).append("'>")
                    .append(AdminConsole.escape(bucket.name())).append("</a></td><td>")
                    .append(versioning(bucket.versioning())).append("</td><td>")
                    .append(ownership(bucket.ownership())).append("</td><td>")
                    .append(time(bucket.created())).append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        if (after != null || next) {
            html.append("<nav class=pagination aria-label='Bucket pages'><a class=button-link href='")
                .append(indexUrl(filter, null)).append("'>First page</a>");
            if (next) html.append("<a class=button-link href='")
                .append(indexUrl(filter, page.getLast().name())).append("'>Next page →</a>");
            html.append("</nav>");
        }
        return html.toString();
    }

    static String bucket(ObjectStorage store, String rawQuery, String csrf) throws IOException {
        Map<String, String> query = query(rawQuery, Set.of("name", "prefix", "after"));
        String name = required(query, "name", 255);
        String prefix = query.getOrDefault("prefix", "");
        String after = query.get("after");
        if (prefix.length() > 1024 || after != null && after.length() > 1024)
            throw invalidQuery();
        ObjectStorage.Bucket bucket = store.bucket(name);
        ObjectStorage.ListPage page = store.list(name, prefix, "/", PAGE_SIZE, after);
        long visibleBytes = page.objects().stream().mapToLong(entry -> entry.metadata().length()).sum();

        StringBuilder html = new StringBuilder(AdminConsole.consoleHeader(csrf));
        html.append(breadcrumbs(name, prefix));
        html.append("<div class=intro><div><h2>").append(AdminConsole.escape(name))
            .append("</h2><p>Browse prefixes and inspect object metadata.</p></div>")
            .append("<biscuit-badgeview theme=dark variant=accent>Read-only</biscuit-badgeview></div>")
            .append("<div class=grid><biscuit-cardview><small>Entries on this page</small><strong>")
            .append(page.keyCount()).append("</strong></biscuit-cardview>")
            .append("<biscuit-cardview><small>Object bytes on this page</small><strong>")
            .append(AdminConsole.formatBytes(visibleBytes)).append("</strong></biscuit-cardview>")
            .append("<biscuit-cardview><small>Versioning</small><strong>")
            .append(versioning(bucket.versioning())).append("</strong></biscuit-cardview></div>")
            .append("<section class=buckets id=objects><h2>Objects</h2>")
            .append("<p>Showing up to ").append(PAGE_SIZE)
            .append(" entries in this prefix. Counts and chart data describe this page only.</p>")
            .append("<div class=toolbar><form method=get action='").append(AdminConsole.PATH)
            .append("/bucket'><input type=hidden name=name value='").append(AdminConsole.escape(name))
            .append("'><div class=field><label for=prefix>Prefix</label>")
            .append("<input id=prefix name=prefix value='").append(AdminConsole.escape(prefix))
            .append("' placeholder='e.g. images/2026/'></div><button type=submit>Browse</button></form>")
            .append("<a class=button-link href='").append(AdminConsole.PATH)
            .append("/buckets'>All buckets</a></div>");
        if (page.keyCount() == 0) {
            html.append("<div class='table-wrap empty'>Nothing in this prefix.</div>");
            if (!prefix.isEmpty()) html.append("<nav class=pagination><a class=button-link href='")
                .append(bucketUrl(name, parentPrefix(prefix), null)).append("'>← Parent folder</a></nav>");
        } else {
            html.append("<div class=table-wrap><table><thead><tr><th>Name</th><th>Kind</th>")
                .append("<th>Size</th><th>Modified</th></tr></thead><tbody>");
            if (!prefix.isEmpty()) {
                html.append("<tr><td class=name-cell><a href='")
                    .append(bucketUrl(name, parentPrefix(prefix), null))
                    .append("'>../</a></td><td class=kind>Parent</td><td>—</td><td>—</td></tr>");
            }
            for (String folder : page.prefixes()) {
                html.append("<tr><td class=name-cell><a href='")
                    .append(bucketUrl(name, folder, null)).append("'>▸ ")
                    .append(AdminConsole.escape(folder.substring(prefix.length())))
                    .append("</a></td><td class=kind>Folder</td><td>—</td><td>—</td></tr>");
            }
            for (ObjectStorage.ListedObject object : page.objects()) {
                ObjectStorage.Metadata meta = object.metadata();
                String label = object.key().substring(prefix.length());
                if (label.isEmpty()) label = "(folder marker)";
                html.append("<tr><td class=name-cell><a href='")
                    .append(objectUrl(name, object.key())).append("'>")
                    .append(AdminConsole.escape(label)).append("</a></td><td class=kind>Object</td><td>")
                    .append(AdminConsole.formatBytes(meta.length())).append("</td><td>")
                    .append(time(meta.modified())).append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        if (after != null || page.truncated()) {
            html.append("<nav class=pagination aria-label='Object pages'><a class=button-link href='")
                .append(bucketUrl(name, prefix, null)).append("'>First page</a>");
            if (page.truncated()) html.append("<a class=button-link href='")
                .append(bucketUrl(name, prefix, page.nextKey())).append("'>Next page →</a>");
            html.append("</nav>");
        }
        html.append("</section>");
        if (!page.objects().isEmpty()) {
            html.append("<section class=insight><biscuit-chartview type=bar theme=dark ")
                .append("name='Largest objects on this page' height=300>")
                .append("<biscuit-series type=bar x=label y=bytes name='Size in bytes'>");
            page.objects().stream().sorted(Comparator.comparingLong((ObjectStorage.ListedObject object) ->
                object.metadata().length()).reversed()).limit(10).forEach(object -> {
                String label = object.key().substring(prefix.length());
                if (label.length() > 24) label = label.substring(0, 23) + "…";
                html.append("<biscuit-point x='").append(AdminConsole.escape(label))
                    .append("' y='").append(object.metadata().length()).append("'></biscuit-point>");
            });
            html.append("</biscuit-series></biscuit-chartview></section>");
        }
        html.append("<section class=buckets id=configuration><h2>Bucket settings</h2>")
            .append("<div class=detail-grid><div class=detail-card><h3>Access</h3>")
            .append(fact("Ownership", ownership(bucket.ownership())))
            .append(fact("Block public ACLs", yesNo(bucket.publicAccessBlock().blockPublicAcls())))
            .append(fact("Ignore public ACLs", yesNo(bucket.publicAccessBlock().ignorePublicAcls())))
            .append(fact("Block public policy", yesNo(bucket.publicAccessBlock().blockPublicPolicy())))
            .append(fact("Restrict public buckets", yesNo(bucket.publicAccessBlock().restrictPublicBuckets())))
            .append("</div><div class=detail-card><h3>Lifecycle</h3>")
            .append(fact("Created", time(bucket.created())))
            .append(fact("Versioning", versioning(bucket.versioning())))
            .append(fact("Policy", bucket.policy() == null ? "None" : "Configured"))
            .append("</div></div>");
        if (bucket.policy() != null)
            html.append("<details><summary>Bucket policy</summary><pre>")
                .append(AdminConsole.escape(bucket.policy())).append("</pre></details>");
        if (!bucket.acl().isEmpty())
            html.append(attributes("Bucket grants", bucket.acl()));
        html.append("</section>");
        return html.toString();
    }

    static String object(ObjectStorage store, String rawQuery, String csrf) throws IOException {
        Map<String, String> query = query(rawQuery, Set.of("bucket", "key"));
        String bucketName = required(query, "bucket", 255);
        String key = required(query, "key", 1024);
        store.bucket(bucketName);
        ObjectStorage.Metadata meta;
        try (ObjectStorage.OpenObject opened = store.open(bucketName, key)) {
            meta = opened.metadata();
        }
        int slash = key.lastIndexOf('/');
        String prefix = slash < 0 ? "" : key.substring(0, slash + 1);
        StringBuilder html = new StringBuilder(AdminConsole.consoleHeader(csrf));
        html.append(breadcrumbs(bucketName, prefix))
            .append("<div class=intro><div><h2 class=name-cell>")
            .append(AdminConsole.escape(key.endsWith("/") ? "(folder marker)" : key.substring(prefix.length())))
            .append("</h2><p>Object metadata · content is not loaded in this view</p></div>")
            .append("<biscuit-badgeview theme=dark variant=accent>Object</biscuit-badgeview></div>")
            .append("<div class=grid><biscuit-cardview><small>Size</small><strong>")
            .append(AdminConsole.formatBytes(meta.length()))
            .append("</strong></biscuit-cardview><biscuit-cardview><small>Content type</small><strong>")
            .append(AdminConsole.escape(meta.contentType() == null ? "Unknown" : meta.contentType()))
            .append("</strong></biscuit-cardview><biscuit-cardview><small>Modified</small><strong>")
            .append(time(meta.modified())).append("</strong></biscuit-cardview></div>")
            .append("<div class=detail-grid><div class=detail-card><h3>Identity</h3>")
            .append(fact("Bucket", bucketName)).append(fact("Key", key))
            .append(fact("Version ID", meta.versionId() == null ? "Unversioned" : meta.versionId()))
            .append("</div><div class=detail-card><h3>Integrity</h3>")
            .append(fact("ETag", meta.etag() == null ? "—" : meta.etag()))
            .append(fact("SHA-256", meta.sha256() == null ? "—" :
                java.util.HexFormat.of().formatHex(meta.sha256())))
            .append("</div></div>")
            .append(attributes("User metadata", meta.userMetadata()))
            .append(attributes("Tags", meta.tags()))
            .append(attributes("Checksums", meta.checksums()))
            .append(attributes("ACL grants", meta.acl()));
        return html.toString();
    }

    static String bucketUrl(String name, String prefix, String after) {
        StringBuilder url = new StringBuilder(AdminConsole.PATH + "/bucket?name=" + encoded(name));
        if (!prefix.isEmpty()) url.append("&prefix=").append(encoded(prefix));
        if (after != null) url.append("&after=").append(encoded(after));
        return url.toString();
    }

    private static String objectUrl(String bucket, String key) {
        return AdminConsole.PATH + "/object?bucket=" + encoded(bucket) + "&key=" + encoded(key);
    }

    private static String indexUrl(String filter, String after) {
        StringBuilder url = new StringBuilder(AdminConsole.PATH + "/buckets?filter=" + encoded(filter));
        if (after != null) url.append("&after=").append(encoded(after));
        return url.toString();
    }

    private static String breadcrumbs(String bucket, String prefix) {
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

    private static String parentPrefix(String prefix) {
        int last = prefix.lastIndexOf('/', prefix.length() - 2);
        return last < 0 ? "" : prefix.substring(0, last + 1);
    }

    private static String attributes(String title, Map<String, String> values) {
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

    private static String fact(String name, String value) {
        return "<div class=fact><span>" + AdminConsole.escape(name) + "</span><strong>" +
            AdminConsole.escape(value) + "</strong></div>";
    }

    private static String versioning(ObjectStorage.VersioningState state) {
        return switch (state) {
            case NEVER -> "Off";
            case ENABLED -> "Enabled";
            case SUSPENDED -> "Suspended";
        };
    }

    private static String ownership(ObjectStorage.ObjectOwnership value) {
        return switch (value) {
            case BUCKET_OWNER_ENFORCED -> "Owner enforced";
            case BUCKET_OWNER_PREFERRED -> "Owner preferred";
            case OBJECT_WRITER -> "Object writer";
        };
    }

    private static String yesNo(boolean value) {
        return value ? "On" : "Off";
    }

    private static String time(long epochMillis) {
        return TIME.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String required(Map<String, String> values, String name, int limit) {
        String value = values.get(name);
        if (value == null || value.isEmpty() || value.length() > limit) throw invalidQuery();
        return value;
    }

    private static Map<String, String> query(String raw, Set<String> allowed) {
        if (raw == null || raw.isEmpty()) return Map.of();
        if (raw.length() > 8192) throw invalidQuery();
        Map<String, String> values = new HashMap<>();
        try {
            for (String field : raw.split("&", -1)) {
                int equal = field.indexOf('=');
                if (equal < 0) throw invalidQuery();
                String name = URLDecoder.decode(field.substring(0, equal), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(field.substring(equal + 1), StandardCharsets.UTF_8);
                if (!allowed.contains(name) || values.putIfAbsent(name, value) != null)
                    throw invalidQuery();
            }
        } catch (IllegalArgumentException badEncoding) {
            throw invalidQuery();
        }
        return values;
    }

    private static StoreException invalidQuery() {
        return new StoreException(400, "InvalidArgument", "Invalid browser link or search");
    }
}
