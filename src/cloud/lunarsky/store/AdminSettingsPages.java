package cloud.lunarsky.store;

import java.io.IOException;
import java.util.List;
import static cloud.lunarsky.store.AdminConsole.PATH;
import static cloud.lunarsky.store.AdminLayout.*;

final class AdminSettingsPages {
    private AdminSettingsPages() { }

    static String credentialsPage(ManagedCredentials managedCredentials, ObjectStorage store, String csrf, ManagedCredentials.Key created) throws IOException {
        StringBuilder content = new StringBuilder(consoleHeader(csrf));
        content.append("<div class=intro><div><h2>Access keys</h2><p>Scoped S3 credentials for applications</p></div></div>");
        ManagedCredentials managed = managedCredentials;
        if (managed == null) {
            content.append("<p class=muted>Managed keys are disabled. Configure a separate master key file to enable them.</p>");
            return page(content.toString(), true);
        }
        if (created != null) {
            content.append("<section class=settings-form><h3>Save this secret now</h3>")
                .append("<p>The secret will not be displayed again.</p><div class=fact><span>Access key ID</span><strong><code>")
                .append(escape(created.id())).append("</code></strong></div>")
                .append("<div class=fact><span>Secret access key</span><strong><code>")
                .append(escape(created.secret())).append("</code></strong></div></section>");
        }
        content.append("<form class=settings-form method=post action='").append(PATH)
            .append("/action/create-key'><h3>Create scoped key</h3><p>Select one bucket and an optional key prefix.</p>")
            .append("<input type=hidden name=csrf value='").append(csrf).append("'>")
            .append("<label for=key-bucket>Bucket</label><select id=key-bucket name=bucket required>");
        for (ObjectStorage.Bucket bucket : store.buckets())
            content.append("<option value='").append(escape(bucket.name())).append("'>")
                .append(escape(bucket.name())).append("</option>");
        content.append("</select><label for=key-prefix>Key prefix</label>")
            .append("<input id=key-prefix name=prefix maxlength=256 placeholder='images/'>")
            .append("<div class=check-grid><label class=check><input type=checkbox name=read>Read</label>")
            .append("<label class=check><input type=checkbox name=write>Write</label>")
            .append("<label class=check><input type=checkbox name=list>List</label></div>")
            .append("<button type=submit>").append(icon("access-key"))
            .append("Create key</button></form>")
            .append("<section class=buckets><h2>Managed keys</h2><p>Disabling a key rejects new signed requests immediately.</p>");
        for (ManagedCredentials.Key key : managed.list()) {
            content.append("<div class=settings-form><h3><code>").append(escape(key.id()))
                .append("</code> <biscuit-badgeview theme=deep-dark variant=")
                .append(key.enabled() ? "success" : "warning").append(" size=small>")
                .append(key.enabled() ? "Active" : "Disabled")
                .append("</biscuit-badgeview></h3><p>")
                .append(escape(key.bucket())).append(" / ")
                .append(escape(key.prefix())).append(" · ")
                .append(escape(String.join(", ", key.actions().stream().sorted().toList())))
                .append("</p>")
                .append("<form method=post action='").append(PATH).append("/action/update-key'>")
                .append("<input type=hidden name=csrf value='").append(csrf).append("'>")
                .append("<input type=hidden name=id value='").append(key.id()).append("'>")
                .append("<label>Bucket<input name=bucket required value='").append(escape(key.bucket()))
                .append("'></label><label>Prefix<input name=prefix maxlength=256 value='")
                .append(escape(key.prefix())).append("'></label><div class=check-grid>");
            for (String permission : List.of("read", "write", "list"))
                content.append("<label class=check><input type=checkbox name=").append(permission)
                    .append(key.actions().contains(permission) ? " checked" : "")
                    .append(">").append(permission).append("</label>");
            content.append("</div><label>Type the access key ID to confirm changes")
                .append("<input name=confirm required autocomplete=off></label>")
                .append("<button type=submit>Save scope</button></form>")
                .append("<form method=post action='").append(PATH).append("/action/")
                .append(key.enabled() ? "disable-key" : "enable-key").append("'>")
                .append("<input type=hidden name=csrf value='").append(csrf).append("'>")
                .append("<input type=hidden name=id value='").append(key.id()).append("'>")
                .append("<label>Type the access key ID to confirm<input name=confirm required autocomplete=off></label>")
                .append("<button type=submit>").append(key.enabled() ? "Disable" : "Enable")
                .append("</button></form><form method=post action='").append(PATH)
                .append("/action/delete-key'><input type=hidden name=csrf value='").append(csrf)
                .append("'><input type=hidden name=id value='").append(key.id())
                .append("'><label>Type the access key ID to delete<input name=confirm required autocomplete=off></label>")
                .append("<button type=submit>Delete key</button></form></div>");
        }
        content.append("</section>");
        return page(content.toString(), true);
    }

    static String settingsPage(String csrf) {
        return consoleHeader(csrf) +
            "<nav class=crumbs aria-label=Breadcrumb><a href='" + PATH +
            "/overview'>Overview</a><span>/</span><span>Settings</span></nav>" +
            "<div class=intro><div><h2>Appearance</h2>" +
            "<p>Choose a theme for this browser or set the default for all admins.</p></div></div>" +
            "<section class=settings-grid><div class=settings-form>" +
            "<h3>Theme</h3><label for=admin-theme>This browser</label>" +
            "<select id=admin-theme><option value=default>Use server default</option>" +
            "<option value=dark>Dark</option>" +
            "<option value=auto>Follow system</option>" +
            "<option value=light>Light</option>" +
            "<option value=deep-dark>Deep dark</option></select>" +
            "<p class=muted>Your choice is saved only in this browser.</p>" +
            "<form method=post action='" + PATH + "/action/default-theme'>" +
            "<input type=hidden name=csrf value='" + csrf + "'>" +
            "<label for=admin-global-theme>Global default</label>" +
            "<select id=admin-global-theme name=defaultTheme>" +
            "<option value=deep-dark>Deep dark</option>" +
            "<option value=dark>Dark</option>" +
            "<option value=light>Light</option>" +
            "<option value=auto>Follow system</option></select>" +
            "<button type=submit>Save global default</button></form>" +
            "<p class=muted>Used by browsers without a personal theme choice.</p></div>" +
            "<div class=settings-form><h3>Branding</h3>" +
            "<form method=post action='" + PATH + "/action/branding-name'>" +
            "<input type=hidden name=csrf value='" + csrf + "'>" +
            "<label for=admin-brand-text>Display name</label>" +
            "<input id=admin-brand-text name=displayName maxlength=40 required placeholder='ObjectStore admin'>" +
            "<button type=submit>Save name</button></form>" +
            "<label for=admin-brand-icon>Icon image</label>" +
            "<input id=admin-brand-icon type=file data-csrf='" + csrf +
            "' accept='image/png,image/jpeg,image/webp'>" +
            "<div class=brand-preview><img id=admin-brand-preview src='" + PATH +
            "/logo.png' alt='Current admin icon' width=52 height=52>" +
            "<span id=admin-brand-preview-text>ObjectStore admin</span></div>" +
            "<p class=muted>PNG, JPEG, or WebP, up to 128 KiB. Changes appear on every device.</p>" +
            "<form method=post action='" + PATH + "/action/branding-reset'>" +
            "<input type=hidden name=csrf value='" + csrf + "'>" +
            "<button id=admin-brand-reset type=submit>Reset branding</button></form>" +
            "<p id=admin-settings-status role=status></p></div></section>";
    }
}
