package cloud.lunarsky.store;

import java.util.Set;
import static cloud.lunarsky.store.AdminConsole.PATH;

final class AdminLayout {
    static final Set<String> ICONS = Set.of("folder", "file", "refresh", "code-file",
        "search", "chevron-right", "objects", "bucket", "access-key", "activity", "sliders",
        "plus", "upload", "download", "version-history", "edit", "trash");

    private AdminLayout() { }

    static String page(String content) {
        return page(content, false);
    }

    static String page(String content, boolean widgets) {
        return page(content, widgets, false);
    }

    static String page(String content, boolean widgets, boolean performance) {
        boolean workspace = content.startsWith("<!--admin-shell-->");
        return "<!doctype html><html lang=en><head><meta charset=utf-8>" +
            "<meta name=viewport content='width=device-width,initial-scale=1'><title>ObjectStore admin</title>" +
            "<link rel=stylesheet href='" + PATH + "/style.css'>" +
            (widgets || workspace ? "<script defer src='" + PATH + "/biscuit.js'></script>" : "") +
            "<script defer src='" + PATH + "/workspace.js'></script>" +
            (performance ? "<script defer src='" + PATH + "/performance.js'></script>" : "") +
            "</head><body data-admin-theme=deep-dark" + (workspace ? " class=workspace-body" : "") + "><main>" +
            content + (workspace ? "</div><biscuit-toastview id=admin-toast theme=deep-dark></biscuit-toastview>" +
                "<biscuit-footerview theme=deep-dark brand='ObjectStore admin' " +
                "description='Private service controls' copyright='ObjectStore v" + Version.VALUE + "'>" +
                "<a href='https://lunarsky.cloud/objectstore/' data-footer-group=Resources>Documentation</a>" +
                "</biscuit-footerview></div></div>" : "") + "</main></body></html>";
    }

    static String consoleHeader(String csrf) {
        return "<!--admin-shell--><div class=app-shell>" +
            "<biscuit-sidebarview theme=deep-dark label='Navigation' aria-label='Admin sections' sticky>" +
            "<div class=sidebar-brand>" + logoMark() +
            "<span><strong>ObjectStore</strong><small>ADMIN · v" + Version.VALUE + "</small></span></div>" +
            "<div class=sidebar-group>WORKSPACE</div>" +
            navLink("overview", "objects", "Overview") +
            navLink("buckets", "bucket", "Buckets") +
            "<div class=sidebar-group>OPERATIONS</div>" +
            navLink("credentials", "access-key", "Access keys") +
            navLink("performance", "activity", "Performance") +
            navLink("diagnostics", "search", "Diagnostics") +
            navLink("health", "refresh", "Health & recovery") +
            navLink("audit", "access-key", "Audit & access") +
            navLink("jobs", "refresh", "Bulk jobs") +
            navLink("cluster", "objects", "Cluster") +
            "<div class=sidebar-group>PREFERENCES</div>" +
            navLink("settings", "sliders", "Settings") +
            "</biscuit-sidebarview><div class=workspace>" +
            "<biscuit-pageloaderview id=page-loading mode=page theme=deep-dark open " +
            "message='Loading page…'><div class=loader-skeletons>" +
            "<biscuit-skeletonview width=220px height=18px></biscuit-skeletonview>" +
            "<biscuit-skeletonview width=160px height=12px></biscuit-skeletonview>" +
            "</div></biscuit-pageloaderview>" +
            "<biscuit-navbarview theme=deep-dark sticky brand='ObjectStore admin' brand-href='" + PATH +
            "/overview' aria-label='Admin toolbar'>" +
            mobileNavLink("overview", "objects", "Overview") +
            mobileNavLink("buckets", "bucket", "Buckets") +
            mobileNavLink("credentials", "access-key", "Access keys") +
            mobileNavLink("performance", "activity", "Performance") +
            mobileNavLink("diagnostics", "search", "Diagnostics") +
            mobileNavLink("health", "refresh", "Health & recovery") +
            mobileNavLink("audit", "access-key", "Audit & access") +
            mobileNavLink("jobs", "refresh", "Bulk jobs") +
            mobileNavLink("cluster", "objects", "Cluster") +
            mobileNavLink("settings", "sliders", "Settings") +
            "<form slot=actions method=post action='" + PATH +
            "/logout'><input type=hidden name=csrf value='" + csrf +
            "'><button type=submit>Sign out</button></form></biscuit-navbarview>" +
            "<div class=workspace-content>";
    }

    static String navLink(String path, String icon, String label) {
        return "<a class=sidebar-link href='" + PATH + "/" + path + "'><img alt='' src='" + PATH +
            "/icons/" + icon + ".svg' width=18 height=18><span>" + label + "</span></a>";
    }

    static String icon(String name) {
        if (!ICONS.contains(name)) throw new IllegalArgumentException("Unknown admin icon");
        return "<img class=action-icon src='" + PATH + "/icons/" + name +
            ".svg' alt='' width=16 height=16>";
    }

    static String mobileNavLink(String path, String icon, String label) {
        return "<a class=mobile-nav-link href='" + PATH + "/" + path +
            "'><img alt='' src='" + PATH + "/icons/" + icon +
            ".svg' width=16 height=16>" + label + "</a>";
    }

    static String logoMark() {
        return "<span class=mark aria-hidden=true><img src='" + PATH +
            "/logo.png' alt='' width=56 height=56></span>";
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String formatBytes(long bytes) {
        if (bytes == Long.MAX_VALUE) return "Unlimited";
        if (bytes < 1024) return bytes + " B";
        String[] units = { "KiB", "MiB", "GiB", "TiB", "PiB", "EiB" };
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
