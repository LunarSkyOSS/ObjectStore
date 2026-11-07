package cloud.lunarsky.store;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static cloud.lunarsky.store.AdminBrowserView.*;

final class AdminBrowser {
    private static final int PAGE_SIZE = 50;

    private AdminBrowser() { }

    record EditorDraft(String value, String error) { }

    static String index(ObjectStorage store, String rawQuery, String csrf) throws IOException {
        Map<String, String> query = query(rawQuery, Set.of("filter", "after", "page"));
        String filter = query.getOrDefault("filter", "").trim();
        String after = query.get("after");
        String pageQuery = query.get("page");
        if (filter.length() > 256 || after != null && after.length() > 255)
            throw invalidQuery();
        if (after != null && pageQuery != null) throw invalidQuery();
        List<ObjectStorage.Bucket> matches = store.buckets().stream()
            .filter(bucket -> bucket.name().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))
            .sorted(Comparator.comparing(ObjectStorage.Bucket::name)).toList();
        int pageNumber = 1;
        if (pageQuery != null) {
            try { pageNumber = Integer.parseInt(pageQuery); }
            catch (NumberFormatException error) { throw invalidQuery(); }
            if (pageNumber < 1 || pageNumber > Math.max(1, (matches.size() + PAGE_SIZE - 1) / PAGE_SIZE))
                throw invalidQuery();
        } else if (after != null) {
            pageNumber = 1 + (int) matches.stream()
                .filter(bucket -> bucket.name().compareTo(after) <= 0).count() / PAGE_SIZE;
        }
        int offset = (pageNumber - 1) * PAGE_SIZE;
        List<ObjectStorage.Bucket> page = after == null ?
            matches.stream().skip(offset).limit(PAGE_SIZE).toList() :
            matches.stream().filter(bucket -> bucket.name().compareTo(after) > 0)
                .limit(PAGE_SIZE).toList();

        StringBuilder html = new StringBuilder(AdminConsole.consoleHeader(csrf));
        html.append("<nav class=crumbs aria-label=Breadcrumb><a href='")
            .append(AdminConsole.PATH).append("/overview'>Overview</a><span>/</span><span>Buckets</span></nav>")
            .append("<div class=intro><div><h2>Buckets</h2><p>")
            .append(matches.size()).append(" matching buckets</p></div>")
            .append("<button type=button class=primary-action data-open-dialog=create-bucket-dialog>")
            .append(AdminConsole.icon("plus")).append("New bucket</button></div>")
            .append("<div class=toolbar><form method=get action='").append(AdminConsole.PATH)
            .append("/buckets'><div class=field><label for=bucket-filter>Find a bucket</label>")
            .append("<input id=bucket-filter name=filter value='").append(AdminConsole.escape(filter))
            .append("' placeholder='Bucket name'></div><button type=submit>Search</button></form>")
            .append("<a class=button-link href='").append(AdminConsole.PATH)
            .append("/overview'>Overview</a></div>")
            .append(AdminBucketForms.create(csrf));
        if (page.isEmpty()) {
            html.append("<div class='table-wrap empty'>No buckets match this search.</div>");
        } else {
            html.append("<div class=table-wrap><table><thead><tr><th>Bucket</th><th>Versioning</th>")
                .append("<th>Access</th><th>Ownership</th><th>Created</th></tr></thead><tbody>");
            for (ObjectStorage.Bucket bucket : page) {
                html.append("<tr><td class=name-cell><a href='")
                    .append(bucketUrl(bucket.name(), "", null)).append("'>")
                    .append(AdminConsole.escape(bucket.name())).append("</a></td><td>")
                    .append(versioningBadge(bucket.versioning())).append("</td><td>")
                    .append(accessBadge(bucket)).append("</td><td>")
                    .append(ownership(bucket.ownership())).append("</td><td>")
                    .append(time(bucket.created())).append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        if (matches.size() > PAGE_SIZE)
            html.append("<biscuit-paginationview class=bucket-pagination theme=deep-dark page=")
                .append(pageNumber).append(" total=").append(matches.size())
                .append(" page-size=").append(PAGE_SIZE).append(" data-filter='")
                .append(AdminConsole.escape(filter)).append("'></biscuit-paginationview>");
        return html.toString();
    }

    static String bucket(ObjectStorage store, String rawQuery, String csrf) throws IOException {
        return bucket(store, rawQuery, csrf, null);
    }

    static String bucket(ObjectStorage store, String rawQuery, String csrf, EditorDraft policyDraft)
        throws IOException {
        return bucket(store, rawQuery, csrf, policyDraft, null);
    }

    static String bucket(ObjectStorage store, String rawQuery, String csrf, EditorDraft policyDraft,
                         BucketActivity activity) throws IOException {
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
            .append("<div class=bucket-badges>")
            .append(versioningBadge(bucket.versioning()))
            .append(accessBadge(bucket))
            .append("</div></div>")
            .append("<div class=grid><biscuit-cardview><small>Entries on this page</small><strong>")
            .append(page.keyCount()).append("</strong></biscuit-cardview>")
            .append("<biscuit-cardview><small>Object bytes on this page</small><strong>")
            .append(AdminConsole.formatBytes(visibleBytes)).append("</strong></biscuit-cardview>")
            .append("<biscuit-cardview><small>Versioning</small><strong>")
            .append(versioning(bucket.versioning())).append("</strong></biscuit-cardview></div>")
            .append("<biscuit-tablayout theme=deep-dark orientation=horizontal variant=underline ")
            .append("aria-label='Bucket panels' value='")
            .append(policyDraft == null ? "objects" : "settings").append("'>");
        objectsPanel(html, store.limits().maxObjectBytes(), name, prefix, after, page, csrf);
        html.append("<biscuit-tab label=Activity value=activity>")
            .append(activityPanel(name, activity));
        largestObjects(html, page, prefix);
        html.append("</biscuit-tab><biscuit-tab label=Settings value=settings>");
        bucketSummary(html, bucket);
        AdminBucketForms.settings(html, bucket, csrf, policyDraft);
        html.append("</biscuit-tab></biscuit-tablayout>");
        return html.toString();
    }

    private static void objectsPanel(StringBuilder html, long maxObjectBytes, String name,
                                     String prefix, String after, ObjectStorage.ListPage page, String csrf) {
        html.append("<biscuit-tab label=Objects value=objects>")
            .append("<section class=buckets id=objects><h2>Objects</h2>")
            .append("<p>Showing up to ").append(PAGE_SIZE)
            .append(" entries in this prefix. Counts and chart data describe this page only.</p>")
            .append("<div class=toolbar><form method=get action='").append(AdminConsole.PATH)
            .append("/bucket'><input type=hidden name=name value='").append(AdminConsole.escape(name))
            .append("'><div class=field><label for=prefix>Prefix</label>")
            .append("<input id=prefix name=prefix value='").append(AdminConsole.escape(prefix))
            .append("' placeholder='e.g. images/2026/'></div><button type=submit>Browse</button></form>")
            .append("<a class=button-link href='").append(AdminConsole.PATH)
            .append("/buckets'>All buckets</a><a class=button-link href='")
            .append(versionsUrl(name, prefix, null, null)).append("'>")
            .append(AdminConsole.icon("version-history")).append("Versions</a></div>")
            .append("<form class='settings-form upload-form' data-bucket='")
            .append(AdminConsole.escape(name)).append("' data-prefix='")
            .append(AdminConsole.escape(prefix)).append("' data-max-bytes='")
            .append(maxObjectBytes).append("'>")
            .append("<input type=hidden name=csrf value='").append(csrf).append("'>")
            .append("<h3>Upload file</h3><div class=upload-fields><label>Choose file")
            .append("<input type=file name=file required></label><label>Object key")
            .append("<input name=key value='").append(AdminConsole.escape(prefix))
            .append("' required placeholder='Path and file name'></label></div>")
            .append("<label class=check><input type=checkbox name=overwrite>Replace existing object")
            .append(" <small>(requires versioning)</small></label>")
            .append("<button type=submit>").append(AdminConsole.icon("upload"))
            .append("Upload</button><p class=upload-status role=status>")
            .append("JavaScript is required for uploads. Maximum file size: ")
            .append(AdminConsole.formatBytes(maxObjectBytes))
            .append(".</p></form>");
        objectTable(html, name, prefix, page);
        if (after != null || page.truncated()) {
            html.append("<nav class=pagination aria-label='Object pages'><a class=button-link href='")
                .append(bucketUrl(name, prefix, null)).append("'>First page</a>");
            if (page.truncated()) html.append("<a class=button-link href='")
                .append(bucketUrl(name, prefix, page.nextKey())).append("'>Next page →</a>");
            html.append("</nav>");
        }
        html.append("</section></biscuit-tab>");
    }

    private static void objectTable(StringBuilder html, String name, String prefix,
                                    ObjectStorage.ListPage page) {
        if (page.keyCount() == 0) {
            html.append("<div class='table-wrap empty'>Nothing in this prefix.</div>");
            if (!prefix.isEmpty()) html.append("<nav class=pagination><a class=button-link href='")
                .append(bucketUrl(name, parentPrefix(prefix), null)).append("'>← Parent folder</a></nav>");
        } else {
            html.append("<div class=table-wrap><table><thead><tr><th>Name</th><th>Kind</th>")
                .append("<th>Size</th><th>Modified</th><th>Actions</th></tr></thead><tbody>");
            if (!prefix.isEmpty()) {
                html.append("<tr><td class=name-cell><a href='")
                    .append(bucketUrl(name, parentPrefix(prefix), null))
                    .append("'>../</a></td><td class=kind>Parent</td><td>—</td><td>—</td><td>—</td></tr>");
            }
            for (String folder : page.prefixes()) {
                html.append("<tr><td class=name-cell><a href='")
                    .append(bucketUrl(name, folder, null)).append("'>▸ ")
                    .append(AdminConsole.escape(folder.substring(prefix.length())))
                    .append("</a></td><td class=kind>Folder</td><td>—</td><td>—</td><td>—</td></tr>");
            }
            for (ObjectStorage.ListedObject object : page.objects()) {
                ObjectStorage.Metadata meta = object.metadata();
                String label = object.key().substring(prefix.length());
                if (label.isEmpty()) label = "(folder marker)";
                html.append("<tr><td class=name-cell><a href='")
                    .append(objectUrl(name, object.key())).append("'>")
                    .append(AdminConsole.escape(label)).append("</a></td><td class=kind>Object</td><td>")
                    .append(AdminConsole.formatBytes(meta.length())).append("</td><td>")
                    .append(time(meta.modified())).append("</td><td><a href='")
                    .append(downloadUrl(name, object.key(), null))
                    .append("'>").append(AdminConsole.icon("download"))
                    .append("Download</a></td></tr>");
            }
            html.append("</tbody></table></div>");
        }
    }

    private static void largestObjects(StringBuilder html, ObjectStorage.ListPage page, String prefix) {
        if (!page.objects().isEmpty()) {
            html.append("<section class=insight><biscuit-chartview type=bar theme=deep-dark ")
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
    }

    private static void bucketSummary(StringBuilder html, ObjectStorage.Bucket bucket) {
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
    }

    private static String activityPanel(String bucket, BucketActivity activity) {
        if (activity == null)
            return "<section class=insight><h2>Daily activity</h2><p>Activity is unavailable.</p></section>";
        List<BucketActivity.Day> days = activity.days(bucket);
        long reads = days.stream().mapToLong(BucketActivity.Day::reads).sum();
        long writes = days.stream().mapToLong(BucketActivity.Day::writes).sum();
        StringBuilder html = new StringBuilder("<section class='insight activity-panel'><h2>Daily activity</h2>")
            .append("<p>Successful S3 requests observed by this server, in UTC. ")
            .append("The latest activity may take up to a minute to survive a sudden power loss.</p>")
            .append("<p class=activity-summary><strong>").append(reads + writes)
            .append(" requests</strong><span>").append(reads).append(" reads · ")
            .append(writes).append(" writes over 365 days</span></p>")
            .append("<biscuit-activity-chart theme=deep-dark name='Daily S3 requests' ")
            .append("calendar-range=rolling calendar-color='#9d73d5' ")
            .append("color-scale=steps gradient=subtle>")
            .append("<biscuit-series type=calendar x=date y=count name=Requests>");
        for (BucketActivity.Day day : days) {
            if (day.total() > 0)
                html.append("<biscuit-point x='").append(day.date()).append("' y='")
                    .append(day.total()).append("'></biscuit-point>");
        }
        return html.append("</biscuit-series></biscuit-activity-chart>")
            .append("<p class=muted>Each square is one UTC day. Counts include reads and writes.</p></section>")
            .toString();
    }

    static String object(ObjectStorage store, String rawQuery, String csrf) throws IOException {
        return AdminObjectPages.object(store, rawQuery, csrf, null);
    }

    static String object(ObjectStorage store, String rawQuery, String csrf, EditorDraft contentDraft)
        throws IOException {
        return AdminObjectPages.object(store, rawQuery, csrf, contentDraft);
    }

    static String versions(ObjectStorage store, String rawQuery, String csrf) throws IOException {
        return AdminObjectPages.versions(store, rawQuery, csrf);
    }

    static String bucketUrl(String name, String prefix, String after) {
        return AdminBrowserView.bucketUrl(name, prefix, after);
    }

    static String encoded(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String required(Map<String, String> values, String name, int limit) {
        String value = values.get(name);
        if (value == null || value.isEmpty() || value.length() > limit) throw invalidQuery();
        return value;
    }

    static Map<String, String> query(String raw, Set<String> allowed) {
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

    static StoreException invalidQuery() {
        return new StoreException(400, "InvalidArgument", "Invalid browser link or search");
    }
}
