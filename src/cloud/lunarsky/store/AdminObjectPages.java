package cloud.lunarsky.store;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import static cloud.lunarsky.store.AdminBrowser.*;
import static cloud.lunarsky.store.AdminBrowserView.*;
import static cloud.lunarsky.store.AdminBucketForms.*;

final class AdminObjectPages {
    private static final int PAGE_SIZE = 50;

    private AdminObjectPages() { }

    static String object(ObjectStorage store, String rawQuery, String csrf) throws IOException {
        return object(store, rawQuery, csrf, null);
    }

    static String object(ObjectStorage store, String rawQuery, String csrf, AdminBrowser.EditorDraft contentDraft)
        throws IOException {
        Map<String, String> query = query(rawQuery, Set.of("bucket", "key", "version"));
        String bucketName = required(query, "bucket", 255);
        String key = required(query, "key", 1024);
        String version = query.get("version");
        if (version != null && (version.isEmpty() || version.length() > 256)) throw invalidQuery();
        ObjectStorage.Bucket bucket = store.bucket(bucketName);
        ObjectStorage.Metadata meta;
        String text = null;
        String previewError = null;
        try (ObjectStorage.OpenObject opened = store.open(bucketName, key, version)) {
            meta = opened.metadata();
            if (AdminTextEditor.supports(meta)) {
                try { text = AdminTextEditor.read(opened); }
                catch (StoreException error) { previewError = error.getMessage(); }
            }
        }
        int slash = key.lastIndexOf('/');
        String prefix = slash < 0 ? "" : key.substring(0, slash + 1);
        StringBuilder html = new StringBuilder(AdminConsole.consoleHeader(csrf));
        html.append(breadcrumbs(bucketName, prefix))
            .append("<div class=intro><div><h2 class=name-cell>")
            .append(AdminConsole.escape(key.endsWith("/") ? "(folder marker)" : key.substring(prefix.length())))
            .append("</h2><p>Inspect, download, or edit this object.</p></div>")
            .append("<biscuit-badgeview theme=deep-dark variant=accent>Object</biscuit-badgeview></div>")
            .append("<div class=grid><biscuit-cardview><small>Size</small><strong>")
            .append(AdminConsole.formatBytes(meta.length()))
            .append("</strong></biscuit-cardview><biscuit-cardview><small>Content type</small><strong>")
            .append(AdminConsole.escape(meta.contentType() == null ? "Unknown" : meta.contentType()))
            .append("</strong></biscuit-cardview><biscuit-cardview><small>Modified</small><strong>")
            .append(time(meta.modified())).append("</strong></biscuit-cardview></div>")
            .append("<p class=object-actions><a class=button-link href='")
            .append(downloadUrl(bucketName, key, version)).append("'>")
            .append(AdminConsole.icon("download")).append("Download file</a></p>")
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
        if (text != null) {
            textEditor(html, bucket, meta, version, text, csrf, contentDraft);
        } else if (previewError != null) {
            html.append("<p class=muted>").append(AdminConsole.escape(previewError)).append("</p>");
        }
        if (bucket.ownership() != ObjectStorage.ObjectOwnership.BUCKET_OWNER_ENFORCED)
            grantForm(html, "object-grant", bucketName, key, version, csrf);
        objectActions(html, bucketName, key, version, csrf);
        return html.toString();
    }

    private static void textEditor(StringBuilder html, ObjectStorage.Bucket bucket,
                                   ObjectStorage.Metadata meta, String version, String text,
                                   String csrf, AdminBrowser.EditorDraft draft) {
        boolean versioningEnabled = bucket.versioning() ==
            ObjectStorage.VersioningState.ENABLED;
        boolean editable = version == null && versioningEnabled &&
            meta.versionId() != null && meta.sha256() != null;
        String value = draft == null ? text : draft.value();
        html.append("<section class=editor-section id=text-editor><div class=intro><div><h2>Text object</h2>")
            .append("<p>").append(editable ? "Saving creates a new object version." :
                version != null ? "Older versions are read-only." :
                !versioningEnabled ? "Enable bucket versioning to edit this object safely." :
                "This object has no version or integrity token and is read-only here.")
            .append("</p></div><biscuit-badgeview theme=deep-dark variant=accent>")
            .append(AdminConsole.escape(AdminTextEditor.language(meta)))
            .append("</biscuit-badgeview></div>");
        if (editable) html.append("<form class='settings-form code-form' method=post action='")
            .append(AdminConsole.PATH).append("/action/edit-object'>")
            .append(hidden(csrf, bucket.name()))
            .append("<input type=hidden name=key value='").append(AdminConsole.escape(meta.key()))
            .append("'><input type=hidden name=originalVersion value='")
            .append(AdminConsole.escape(meta.versionId() == null ? "" : meta.versionId()))
            .append("'><input type=hidden name=originalSha256 value='")
            .append(meta.sha256() == null ? "" : SigV4.hex(meta.sha256())).append("'>");
        else html.append("<div class=settings-form>");
        html.append("<label for=object-code>Content</label><biscuit-codeview id=object-code ")
            .append("class=text-editor language='")
            .append(AdminConsole.escape(AdminTextEditor.language(meta)))
            .append("' aria-label='Object content'")
            .append(editable ? "" : " readonly")
            .append(" value='").append(AdminConsole.escape(value)).append("'></biscuit-codeview>")
            .append("<textarea class='code-fallback text-fallback' id=object-content rows=12 ")
            .append(editable ? "name=content" : "readonly")
            .append(">").append(AdminConsole.escape(value)).append("</textarea>")
            .append("<p class=editor-diagnostics data-editor=object-code data-state=")
            .append(draft == null ? "hint" : "error")
            .append(" role=status>")
            .append(AdminConsole.escape(draft == null ? "UTF-8 text · 32 KiB maximum" :
                draft.error())).append("</p>");
        if (editable) html.append("<button type=submit>")
            .append(AdminConsole.icon("edit")).append("Save new version</button></form>");
        else html.append("</div>");
        html.append("</section>");
    }

    private static void objectActions(StringBuilder html, String bucketName, String key,
                                      String version, String csrf) {
        html.append("<section class=buckets><h2>Tags</h2>")
            .append("<form class=settings-form method=post action='").append(AdminConsole.PATH)
            .append("/action/object-tag'>").append(hidden(csrf, bucketName))
            .append("<input type=hidden name=key value='").append(AdminConsole.escape(key))
            .append("'>");
        if (version != null) html.append("<input type=hidden name=version value='")
            .append(AdminConsole.escape(version)).append("'>");
        html.append("<div class=form-row><input name=tag placeholder='Tag name' required maxlength=128>")
            .append("<input name=value placeholder='Value' maxlength=256>")
            .append("<select name=operation><option value=add>Save</option>")
            .append("<option value=remove>Remove</option></select><button>Apply tag</button></div></form></section>")
            .append("<form class='settings-form danger-zone' method=post action='")
            .append(AdminConsole.PATH).append("/action/delete-object'>")
            .append(hidden(csrf, bucketName))
            .append("<input type=hidden name=key value='").append(AdminConsole.escape(key)).append("'>");
        if (version != null) html.append("<input type=hidden name=version value='")
            .append(AdminConsole.escape(version)).append("'>");
        html.append("<h3>Delete object</h3><p>Type the full object key to confirm. ")
            .append(version == null ? "A versioned bucket may create a delete marker." :
                "This deletes the selected version permanently.")
            .append("</p><input name=confirm required placeholder='Full object key'>");
        if (version != null) html.append("<input name=confirmVersion required placeholder='Version ID'>");
        html.append("<button type=submit>").append(AdminConsole.icon("trash"))
            .append("Delete object</button></form>");
    }

    static String versions(ObjectStorage store, String rawQuery, String csrf) throws IOException {
        Map<String, String> query = query(rawQuery, Set.of("bucket", "prefix", "keyMarker", "versionMarker"));
        String bucket = required(query, "bucket", 255);
        String prefix = query.getOrDefault("prefix", "");
        String keyMarker = query.get("keyMarker");
        String versionMarker = query.get("versionMarker");
        if (prefix.length() > 1024 || keyMarker != null && keyMarker.length() > 1024 ||
            versionMarker != null && (versionMarker.isEmpty() || versionMarker.length() > 256))
            throw invalidQuery();
        ObjectStorage.VersionPage page = store.listVersions(bucket, prefix, keyMarker, versionMarker, PAGE_SIZE);
        StringBuilder html = new StringBuilder(AdminConsole.consoleHeader(csrf));
        html.append(breadcrumbs(bucket, prefix))
            .append("<div class=intro><div><h2>Object versions</h2><p>Retained versions and delete markers")
            .append("</p></div></div><div class=toolbar><form method=get action='")
            .append(AdminConsole.PATH).append("/versions'><input type=hidden name=bucket value='")
            .append(AdminConsole.escape(bucket)).append("'><div class=field><label for=version-prefix>Prefix</label>")
            .append("<input id=version-prefix name=prefix value='").append(AdminConsole.escape(prefix))
            .append("' placeholder='e.g. images/'></div><button type=submit>Browse</button></form></div>");
        if (page.entries().isEmpty()) {
            html.append("<div class='table-wrap empty'>No versions in this prefix.</div>");
        } else {
            html.append("<div class=table-wrap><table><thead><tr><th>Key</th><th>Version</th>")
                .append("<th>State</th><th>Modified</th></tr></thead><tbody>");
            for (ObjectStorage.VersionEntry entry : page.entries()) {
                html.append("<tr><td class=name-cell>");
                if (entry.deleteMarker()) html.append(AdminConsole.escape(entry.key()));
                else html.append("<a href='").append(objectUrl(bucket, entry.key()))
                    .append("&amp;version=").append(encoded(entry.versionId())).append("'>")
                    .append(AdminConsole.escape(entry.key())).append("</a>");
                html.append("</td><td class=name-cell><code>").append(AdminConsole.escape(entry.versionId()))
                    .append("</code></td><td>").append(entry.deleteMarker() ? "Delete marker" : "Object")
                    .append(entry.latest() ? " · latest" : "").append("</td><td>")
                    .append(time(entry.modified())).append("</td></tr>");
            }
            html.append("</tbody></table></div>");
        }
        if (page.truncated()) html.append("<nav class=pagination><a class=button-link href='")
            .append(versionsUrl(bucket, prefix, page.nextKey(), page.nextVersionId()))
            .append("'>Next page →</a></nav>");
        return html.toString();
    }

}
