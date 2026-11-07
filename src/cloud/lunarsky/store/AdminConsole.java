package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import cloud.lunarsky.store.AdminAuthentication.Session;
import static cloud.lunarsky.store.AdminHttp.*;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.List;
import java.util.Set;

final class AdminConsole {
    static final String PATH = "/_objectstore/admin";
    @FunctionalInterface
    private interface Route {
        void handle(AdminConsole console, HttpExchange exchange, String origin) throws IOException;
    }

    private static final Map<String, Route> ROUTES = Map.ofEntries(
        Map.entry(PATH, AdminConsole::root),
        Map.entry(PATH + "/", AdminConsole::root),
        Map.entry(PATH + "/login", AdminConsole::loginRoute),
        Map.entry(PATH + "/branding.json", AdminConsole::branding),
        Map.entry(PATH + "/branding-icon", AdminConsole::brandingIcon),
        Map.entry(PATH + "/branding-icon/upload", AdminConsole::uploadBranding),
        Map.entry(PATH + "/performance.json", AdminConsole::performanceJson),
        Map.entry(PATH + "/diagnostics/probe.bin", AdminConsole::probe),
        Map.entry(PATH + "/performance.events", AdminConsole::performanceEvents),
        Map.entry(PATH + "/logout", AdminConsole::logout),
        Map.entry(PATH + "/overview", AdminConsole::overview),
        Map.entry(PATH + "/performance", AdminConsole::performancePage),
        Map.entry(PATH + "/health", AdminConsole::statusPage),
        Map.entry(PATH + "/diagnostics", AdminConsole::statusPage),
        Map.entry(PATH + "/audit", AdminConsole::auditPage),
        Map.entry(PATH + "/jobs", AdminConsole::jobsPage),
        Map.entry(PATH + "/cluster", AdminConsole::clusterPage),
        Map.entry(PATH + "/jobs.json", AdminConsole::jobReport),
        Map.entry(PATH + "/jobs.csv", AdminConsole::jobReport),
        Map.entry(PATH + "/credentials", AdminConsole::credentialsPage),
        Map.entry(PATH + "/download", AdminConsole::download),
        Map.entry(PATH + "/upload", AdminConsole::upload),
        Map.entry(PATH + "/buckets", AdminConsole::browse),
        Map.entry(PATH + "/bucket", AdminConsole::browse),
        Map.entry(PATH + "/versions", AdminConsole::browse),
        Map.entry(PATH + "/object", AdminConsole::browse),
        Map.entry(PATH + "/settings", AdminConsole::settings));

    private final ObjectStorage store;
    private final AdminAuthentication authentication;
    private final AdminAssets assets;
    private final AdminBranding branding;
    private final AdminAuditTrail audit;
    private final AdminBulkJobs jobs;
    private volatile StoreOperations operations = new StoreOperations();
    @FunctionalInterface interface AccessCheck {
        AdminAccessCheck.Result check(String principal, String operation, String bucket, String key)
            throws IOException;
    }
    private volatile AccessCheck accessCheck;
    private volatile RequestMetrics metrics;
    private volatile RequestDiagnostics diagnostics;
    private volatile DiskStore diskStore;
    private volatile BucketActivity bucketActivity;
    private volatile Set<String> identities = Set.of();
    private volatile ManagedCredentials managedCredentials;
    private volatile Runnable refreshCredentials;
    private AdminConsole(ObjectStorage store, AdminAuthentication authentication,
                         AdminAssets assets,
                         AdminBranding branding, AdminAuditTrail audit) {
        this.store = store;
        this.authentication = authentication;
        this.assets = assets;
        this.branding = branding;
        this.audit = audit;
        this.jobs = new AdminBulkJobs(store, completed ->
            auditOutcome("bulk-" + completed.spec().operation(),
                completed.spec().bucket() + "/" + completed.spec().prefix(),
                "background", completed.state() + ":" + completed.succeeded() + ":" + completed.failed()));
    }

    void setOperations(StoreOperations operations) {
        this.operations = operations;
        jobs.setOperations(operations);
    }

    void setMetrics(RequestMetrics metrics) { this.metrics = metrics; }
    void setDiagnostics(RequestDiagnostics diagnostics) { this.diagnostics = diagnostics; }
    void setDiskStore(DiskStore diskStore) { this.diskStore = diskStore; }
    void setBucketActivity(BucketActivity bucketActivity) { this.bucketActivity = bucketActivity; }
    void setIdentities(Set<String> identities) { this.identities = Set.copyOf(identities); }
    void setManagedCredentials(ManagedCredentials managed, Runnable refresh) {
        this.managedCredentials = managed;
        this.refreshCredentials = refresh;
    }
    void setAccessCheck(AccessCheck check) { this.accessCheck = check; }

    static AdminConsole fromEnvironment(Map<String, String> env, ObjectStorage store) throws IOException {
        String enabled = env.getOrDefault("ADMIN_UI_ENABLED", "false");
        if (enabled.equals("false")) return null;
        if (!enabled.equals("true")) throw new IllegalArgumentException("Invalid ADMIN_UI_ENABLED");
        AdminAuthentication authentication = AdminAuthentication.fromEnvironment(env);
        Path stateDirectory = Path.of(env.getOrDefault("ADMIN_UI_STATE_DIR",
            env.getOrDefault("DATA_DIR", "/data")));
        Path brandingFile = Path.of(env.getOrDefault("ADMIN_UI_BRANDING_FILE",
            stateDirectory.resolve("admin-branding.txt").toString()));
        return new AdminConsole(store, authentication,
            new AdminAssets(), new AdminBranding(brandingFile),
            new AdminAuditTrail(stateDirectory));
    }

    void handle(HttpExchange exchange) throws IOException {
        securityHeaders(exchange);
        String origin = authentication.requestOrigin(exchange);
        if (origin == null) {
            send(exchange, 403, page("<h1>Admin console unavailable</h1>" +
                "<p>Open the local console at <code>http://127.0.0.1:&lt;port&gt;" + PATH +
                "/login</code>, or use the configured origin.</p>"), "text/html; charset=utf-8");
            return;
        }
        if (assets.handle(exchange, authentication.session(exchange) != null)) return;
        String path = exchange.getRequestURI().getRawPath();
        if (path.startsWith(PATH + "/action/")) {
            action(exchange, origin);
            return;
        }
        Route route = ROUTES.get(path);
        if (route == null) send(exchange, 404, "Not found", "text/plain; charset=utf-8");
        else route.handle(this, exchange, origin);
    }

    private Session requireSession(HttpExchange exchange, String method) throws IOException {
        if (!exchange.getRequestMethod().equals(method)) {
            methodNotAllowed(exchange, method);
            return null;
        }
        Session session = authentication.session(exchange);
        if (session == null) redirect(exchange, PATH + "/login");
        return session;
    }

    private void root(HttpExchange exchange, String origin) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        if (authentication.session(exchange) != null) redirect(exchange, PATH + "/overview");
        else redirect(exchange, PATH + "/login");
    }

    private void loginRoute(HttpExchange exchange, String origin) throws IOException {
        String method = exchange.getRequestMethod();
        if (method.equals("GET")) {
            send(exchange, 200, loginPage("", authentication.issueLoginToken()), "text/html; charset=utf-8");
            return;
        }
        if (method.equals("POST")) {
            login(exchange);
            return;
        }
        methodNotAllowed(exchange, "GET, POST");
    }

    private void branding(HttpExchange exchange, String origin) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        send(exchange, 200, branding.json(), "application/json; charset=utf-8");
    }

    private void brandingIcon(HttpExchange exchange, String origin) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        AdminBranding.State current = branding.state();
        if (current.icon().length == 0) {
            redirect(exchange, PATH + "/logo.png");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", current.mime());
        exchange.sendResponseHeaders(200, current.icon().length);
        exchange.getResponseBody().write(current.icon());
    }

    private void uploadBranding(HttpExchange exchange, String origin) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("POST")) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        Session current = authentication.session(exchange);
        if (current == null) {
            send(exchange, 401, "Sign in required", "text/plain; charset=utf-8");
            return;
        }
        String csrf = exchange.getRequestHeaders().getFirst("X-CSRF-Token");
        if (csrf == null || !MessageDigest.isEqual(current.csrf().getBytes(StandardCharsets.US_ASCII),
                csrf.getBytes(StandardCharsets.US_ASCII)) ||
            !AdminAuthentication.adminOriginAllowed(exchange, origin)) {
            send(exchange, 403, "Invalid admin request", "text/plain; charset=utf-8");
            return;
        }
        byte[] image = exchange.getRequestBody().readNBytes(AdminBranding.MAX_ICON_BYTES + 1);
        try {
            auditStart("branding-icon", "admin", exchange);
            branding.setIcon(image);
            auditOutcome("branding-icon", "admin",
                exchange.getRemoteAddress().getAddress().getHostAddress(), "succeeded");
            send(exchange, 200, branding.json(), "application/json; charset=utf-8");
        } catch (StoreException invalid) {
            auditOutcome("branding-icon", "admin",
                exchange.getRemoteAddress().getAddress().getHostAddress(), invalid.code);
            send(exchange, invalid.status, invalid.getMessage(), "text/plain; charset=utf-8");
        } catch (IOException error) {
            auditOutcome("branding-icon", "admin",
                exchange.getRemoteAddress().getAddress().getHostAddress(), "IOError");
            send(exchange, 503, "Icon could not be saved", "text/plain; charset=utf-8");
        }
    }

    private void performanceJson(HttpExchange exchange, String origin) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        if (authentication.session(exchange) == null) {
            redirect(exchange, PATH + "/login");
            return;
        }
        if (metrics == null) {
            send(exchange, 503, "Metrics unavailable", "text/plain; charset=utf-8");
            return;
        }
        send(exchange, 200, AdminPerformance.snapshot(metrics), "application/json; charset=utf-8");
    }

    private void probe(HttpExchange exchange, String origin) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        if (authentication.session(exchange) == null) {
            redirect(exchange, PATH + "/login");
            return;
        }
        byte[] block = new byte[16 * 1024];
        int remaining = 1024 * 1024;
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.sendResponseHeaders(200, remaining);
        try (OutputStream output = exchange.getResponseBody()) {
            while (remaining > 0) {
                int length = Math.min(block.length, remaining);
                output.write(block, 0, length);
                remaining -= length;
            }
        }
    }

    private void performanceEvents(HttpExchange exchange, String origin) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        if (authentication.session(exchange) == null) {
            redirect(exchange, PATH + "/login");
            return;
        }
        if (metrics == null) {
            send(exchange, 503, "Metrics unavailable", "text/plain; charset=utf-8");
            return;
        }
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            for (int sample = 0; sample < 1800 && authentication.session(exchange) != null; sample++) {
                output.write(("data: " + AdminPerformance.snapshot(metrics) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
                output.flush();
                Thread.sleep(1000);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException disconnected) { return; }
    }

    private void logout(HttpExchange exchange, String origin) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("POST")) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        Session session = authentication.session(exchange);
        Map<String, String> form = readForm(exchange);
        if (session == null || !MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.US_ASCII),
            form.getOrDefault("csrf", "").getBytes(StandardCharsets.US_ASCII))) {
            send(exchange, 403, "Invalid sign-out request", "text/plain; charset=utf-8");
            return;
        }
        authentication.signOut(exchange);
        exchange.getResponseHeaders().add("Set-Cookie", authentication.cookie("", 0));
        redirect(exchange, PATH + "/login");
    }

    private void overview(HttpExchange exchange, String origin) throws IOException {
        Session session = requireSession(exchange, "GET");
        if (session == null) return;
        operations.read(() -> {
            ObjectStorage.Limits limits = store.limits();
            String mode = store instanceof ClusterStore ? "Cluster" : "Disk";
            String status = store.ready() ? "Ready" : "Unavailable";
            List<ObjectStorage.Bucket> buckets;
            boolean bucketListAvailable;
            try {
                buckets = store.buckets();
                bucketListAvailable = true;
            } catch (IOException unavailable) {
                buckets = List.of();
                bucketListAvailable = false;
            }
            send(exchange, 200, AdminStatusPages.overviewPage(mode, status, limits.maxObjectBytes(), limits.maxTotalBytes(),
                buckets, bucketListAvailable, session.csrf()),
                "text/html; charset=utf-8");
        });
    }

    private void performancePage(HttpExchange exchange, String origin) throws IOException {
        Session session = requireSession(exchange, "GET");
        if (session == null) return;
        send(exchange, 200, AdminStatusPages.performancePage(session.csrf()), "text/html; charset=utf-8");
    }

    private void statusPage(HttpExchange exchange, String origin) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        Session session = requireSession(exchange, "GET");
        if (session == null) return;
        operations.read(() -> {
            send(exchange, 200, path.endsWith("/health") ? AdminStatusPages.healthPage(diskStore, store, session.csrf()) :
                AdminStatusPages.diagnosticsPage(diagnostics, session.csrf()), "text/html; charset=utf-8");
        });
    }

    private void auditPage(HttpExchange exchange, String origin) throws IOException {
        Session session = requireSession(exchange, "GET");
        if (session == null) return;
        operations.read(() -> {
            send(exchange, 200, AdminOperationsPages.auditPage(identities, diagnostics, audit, session.csrf(), null, Map.of()), "text/html; charset=utf-8");
        });
    }

    private void jobsPage(HttpExchange exchange, String origin) throws IOException {
        Session session = requireSession(exchange, "GET");
        if (session == null) return;
        send(exchange, 200, AdminOperationsPages.jobsPage(jobs, session.csrf()), "text/html; charset=utf-8");
    }

    private void clusterPage(HttpExchange exchange, String origin) throws IOException {
        Session session = requireSession(exchange, "GET");
        if (session == null) return;
        operations.read(() -> {
            send(exchange, 200, AdminOperationsPages.clusterPage(store, session.csrf()), "text/html; charset=utf-8");
        });
    }

    private void jobReport(HttpExchange exchange, String origin) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getRawPath();
        if (!method.equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        if (authentication.session(exchange) == null) {
            send(exchange, 401, "Sign in required", "text/plain; charset=utf-8");
            return;
        }
        String query = exchange.getRequestURI().getRawQuery();
        String id = query != null && query.matches("id=[A-Za-z0-9_-]{16}") ? query.substring(3) : "";
        try {
            if (path.endsWith(".json")) {
                AdminBulkJobs.Snapshot job = jobs.get(id);
                send(exchange, 200, AdminOperationsPages.jobJson(job), "application/json; charset=utf-8");
            } else {
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=objectstore-job.csv");
                send(exchange, 200, jobs.report(id), "text/csv; charset=utf-8");
            }
        } catch (StoreException error) {
            send(exchange, error.status, "Job not found", "text/plain; charset=utf-8");
        }
    }

    private void credentialsPage(HttpExchange exchange, String origin) throws IOException {
        Session session = requireSession(exchange, "GET");
        if (session == null) return;
        operations.read(() -> {
            send(exchange, 200, AdminSettingsPages.credentialsPage(managedCredentials, store, session.csrf(), null), "text/html; charset=utf-8");
        });
    }

    private void download(HttpExchange exchange, String origin) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        if (authentication.session(exchange) == null) {
            redirect(exchange, PATH + "/login");
            return;
        }
        operations.read(() -> {
            try { AdminFileTransfer.download(store, exchange); }
            catch (StoreException error) {
                if (exchange.getResponseCode() <= 0)
                    send(exchange, error.status, error.getMessage(), "text/plain; charset=utf-8");
            } catch (IOException error) {
                if (exchange.getResponseCode() <= 0)
                    send(exchange, 503, "Download failed; storage is unavailable", "text/plain; charset=utf-8");
            }
        });
    }

    private void upload(HttpExchange exchange, String origin) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("POST")) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        Session session = authentication.session(exchange);
        if (session == null) {
            send(exchange, 401, "Sign in again before uploading", "text/plain; charset=utf-8");
            return;
        }
        String token = exchange.getRequestHeaders().getFirst("X-ObjectStore-CSRF");
        if (token == null || !MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.US_ASCII),
                token.getBytes(StandardCharsets.US_ASCII)) ||
            !AdminAuthentication.adminOriginAllowed(exchange, origin)) {
            send(exchange, 403, "Invalid upload request", "text/plain; charset=utf-8");
            return;
        }
        operations.read(() -> {
            try {
                auditStart("upload-object", "object", exchange);
                String destination = AdminFileTransfer.upload(store, exchange);
                auditOutcome("upload-object", destination,
                    exchange.getRemoteAddress().getAddress().getHostAddress(), "succeeded");
                exchange.getResponseHeaders().set("Location", destination);
                send(exchange, 201, "Uploaded", "text/plain; charset=utf-8");
            } catch (StoreException error) {
                auditOutcome("upload-object", "object",
                    exchange.getRemoteAddress().getAddress().getHostAddress(), error.code);
                send(exchange, error.status, error.getMessage(), "text/plain; charset=utf-8");
            } catch (IOException error) {
                auditOutcome("upload-object", "object",
                    exchange.getRemoteAddress().getAddress().getHostAddress(), "IOError");
                send(exchange, 503, "Upload failed; storage is unavailable", "text/plain; charset=utf-8");
            }
        });
    }

    private void action(HttpExchange exchange, String origin) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        Session session = requireSession(exchange, "POST");
        if (session == null) return;
        Map<String, String> form = readForm(exchange);
        if (!MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.US_ASCII),
            form.getOrDefault("csrf", "").getBytes(StandardCharsets.US_ASCII))) {
            send(exchange, 403, "Invalid admin request", "text/plain; charset=utf-8");
            return;
        }
        if (!AdminAuthentication.adminOriginAllowed(exchange, origin)) {
            send(exchange, 403, "Invalid origin", "text/plain; charset=utf-8");
            return;
        }
        String action = path.substring((PATH + "/action/").length());
        StoreOperations.Action apply = () -> applyAction(exchange, session, action, form);
        try {
            if (action.equals("rename-bucket") || action.equals("delete-bucket")) {
                operations.write(apply);
            } else {
                operations.read(apply);
            }
        } catch (StoreException busy) {
            send(exchange, busy.status, actionErrorPage(action, form, session.csrf(), busy),
                "text/html; charset=utf-8");
        }
    }

    private void applyAction(HttpExchange exchange, Session session, String action,
                             Map<String, String> form) throws IOException {
        String peer = exchange.getRemoteAddress().getAddress().getHostAddress();
        String target = auditTarget(form);
        try {
            if ((action.equals("rename-bucket") || action.equals("delete-bucket")) && jobs.hasActiveJob())
                throw new StoreException(409, "OperationAborted", "Wait for or cancel the active bulk job before renaming or deleting a bucket");
            auditStart(action, target, exchange);
            switch (action) {
                case "access-check" -> checkAccess(exchange, session, action, form);
                case "bulk-start" -> startBulkJob(exchange, session, action, form);
                case "bulk-cancel" -> cancelBulkJob(exchange, session, action, form);
                case "branding-name" -> saveBrandingName(exchange, session, action, form);
                case "branding-reset" -> resetBranding(exchange, session, action, form);
                case "default-theme" -> saveTheme(exchange, session, action, form);
                case "create-key", "update-key", "enable-key", "disable-key", "delete-key" ->
                    changeCredentials(exchange, session, action, form);
                default -> changeStorage(exchange, session, action, form);
            }
        } catch (StoreException error) {
            auditOutcome(action, target, peer, error.code);
            send(exchange, error.status, actionErrorPage(action, form, session.csrf(), error),
                "text/html; charset=utf-8");
        } catch (IOException error) {
            auditOutcome(action, target, peer, "IOError");
            send(exchange, 503, page(consoleHeader(session.csrf()) +
                "<h2>Storage unavailable</h2><p class=error>Change was not saved.</p>"),
                "text/html; charset=utf-8");
        }
    }

    private void checkAccess(HttpExchange exchange, Session session, String action,
                              Map<String, String> form) throws IOException {
        String peer = exchange.getRemoteAddress().getAddress().getHostAddress();
        String target = auditTarget(form);
        AccessCheck check = accessCheck;
        if (check == null) throw new StoreException(503, "ServiceUnavailable", "Access checker unavailable");
        AdminAccessCheck.Result result = check.check(form.get("principal"),
            form.get("operation"), form.get("bucket"), form.get("key"));
        auditOutcome(action, target, peer, result.allowed() ? "allowed" : "denied");
        send(exchange, 200, AdminOperationsPages.auditPage(identities, diagnostics, audit, session.csrf(), result, form), "text/html; charset=utf-8");
    }

    private void startBulkJob(HttpExchange exchange, Session session, String action,
                              Map<String, String> form) throws IOException {
        String peer = exchange.getRemoteAddress().getAddress().getHostAddress();
        String target = auditTarget(form);
        String operation = form.getOrDefault("operation", "");
        if (operation.equals("delete") && !form.getOrDefault("bucket", "").equals(form.get("confirm")))
            throw new StoreException(400, "InvalidArgument", "Type the source bucket to confirm deletion");
        AdminBulkJobs.Spec spec = new AdminBulkJobs.Spec(operation,
            form.getOrDefault("bucket", ""), form.getOrDefault("prefix", ""),
            form.getOrDefault("targetBucket", ""), form.getOrDefault("targetPrefix", ""),
            form.getOrDefault("tag", ""), form.getOrDefault("value", ""));
        AdminBulkJobs.Snapshot job = jobs.start(spec);
        auditOutcome(action, target, peer, "started:" + job.id());
        redirect(exchange, PATH + "/jobs");
    }

    private void cancelBulkJob(HttpExchange exchange, Session session, String action,
                              Map<String, String> form) throws IOException {
        String peer = exchange.getRemoteAddress().getAddress().getHostAddress();
        String target = auditTarget(form);
        jobs.cancel(form.getOrDefault("id", ""));
        auditOutcome(action, "job:" + form.getOrDefault("id", ""), peer, "requested");
        redirect(exchange, PATH + "/jobs");
    }

    private void saveBrandingName(HttpExchange exchange, Session session, String action,
                              Map<String, String> form) throws IOException {
        String peer = exchange.getRemoteAddress().getAddress().getHostAddress();
        String target = auditTarget(form);
        branding.setName(form.get("displayName"));
        auditOutcome(action, target, peer, "succeeded");
        redirect(exchange, PATH + "/settings");
    }

    private void resetBranding(HttpExchange exchange, Session session, String action,
                              Map<String, String> form) throws IOException {
        String peer = exchange.getRemoteAddress().getAddress().getHostAddress();
        String target = auditTarget(form);
        branding.reset();
        auditOutcome(action, target, peer, "succeeded");
        redirect(exchange, PATH + "/settings");
    }

    private void saveTheme(HttpExchange exchange, Session session, String action,
                              Map<String, String> form) throws IOException {
        String peer = exchange.getRemoteAddress().getAddress().getHostAddress();
        String target = auditTarget(form);
        branding.setDefaultTheme(form.get("defaultTheme"));
        auditOutcome(action, target, peer, "succeeded");
        redirect(exchange, PATH + "/settings");
    }

    private void changeCredentials(HttpExchange exchange, Session session, String action,
                              Map<String, String> form) throws IOException {
        String peer = exchange.getRemoteAddress().getAddress().getHostAddress();
        String target = auditTarget(form);
        ManagedCredentials managed = managedCredentials;
        if (managed == null)
            throw new StoreException(409, "InvalidRequest", "Managed keys are not configured");
        ManagedCredentials.Key created = null;
        if (action.equals("create-key")) {
            String bucket = form.getOrDefault("bucket", "");
            store.bucket(bucket);
            created = managed.create(bucket, form.getOrDefault("prefix", ""), keyPermissions(form));
        } else {
            String id = form.getOrDefault("id", "");
            if (!id.matches("[A-Z0-9]{32}") || !id.equals(form.get("confirm")))
                throw new StoreException(400, "InvalidArgument", "Type the access key ID to confirm");
            switch (action) {
                case "update-key" -> {
                    String bucket = form.getOrDefault("bucket", "");
                    store.bucket(bucket);
                    managed.update(id, bucket, form.getOrDefault("prefix", ""), keyPermissions(form));
                }
                case "enable-key" -> managed.setEnabled(id, true);
                case "disable-key" -> managed.setEnabled(id, false);
                case "delete-key" -> managed.remove(id);
                default -> throw new IllegalStateException();
            }
        }
        refreshCredentials.run();
        auditOutcome(action, target, peer, "succeeded");
        System.out.println("ObjectStore admin action " + action + " from " +
            exchange.getRemoteAddress().getAddress().getHostAddress());
        if (created == null) redirect(exchange, PATH + "/credentials");
        else send(exchange, 200, AdminSettingsPages.credentialsPage(managedCredentials, store, session.csrf(), created),
            "text/html; charset=utf-8");
    }

    private void changeStorage(HttpExchange exchange, Session session, String action,
                               Map<String, String> form) throws IOException {
        String peer = exchange.getRemoteAddress().getAddress().getHostAddress();
        String target = auditTarget(form);
        String destination = action.equals("edit-object") ?
            AdminTextEditor.save(store, form) :
                AdminActions.apply(store, action, form, identities, managedCredentials);
        BucketActivity activity = bucketActivity;
        if (activity != null) {
            String bucket = form.get("bucket");
            if (action.equals("rename-bucket")) activity.rename(bucket, form.get("newName"));
            else if (action.equals("delete-bucket")) activity.remove(bucket);
        }
        System.out.println("ObjectStore admin action " + action + " from " +
            exchange.getRemoteAddress().getAddress().getHostAddress());
        auditOutcome(action, target, peer, "succeeded");
        redirect(exchange, destination);
    }

    private void browse(HttpExchange exchange, String origin) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        Session session = requireSession(exchange, "GET");
        if (session == null) return;
        operations.read(() -> {
            try {
                String body = path.equals(PATH + "/buckets") ?
                    AdminBrowser.index(store, exchange.getRequestURI().getRawQuery(), session.csrf()) :
                    path.equals(PATH + "/bucket") ?
                    AdminBrowser.bucket(store, exchange.getRequestURI().getRawQuery(), session.csrf(),
                        null, bucketActivity) :
                    path.equals(PATH + "/versions") ?
                    AdminBrowser.versions(store, exchange.getRequestURI().getRawQuery(), session.csrf()) :
                    AdminBrowser.object(store, exchange.getRequestURI().getRawQuery(), session.csrf());
                send(exchange, 200, page(body, true), "text/html; charset=utf-8");
            } catch (StoreException error) {
                send(exchange, error.status, page(consoleHeader(session.csrf()) +
                    "<div class=crumbs><a href='" + PATH + "/buckets'>Buckets</a></div>" +
                    "<h2>Could not open this view</h2><p class=muted>" + escape(error.getMessage()) + "</p>"),
                    "text/html; charset=utf-8");
            } catch (IOException unavailable) {
                send(exchange, 503, page(consoleHeader(session.csrf()) +
                    "<h2>Storage unavailable</h2><p class=muted>Try again when the service is ready.</p>"),
                    "text/html; charset=utf-8");
            }
        });
    }

    private void settings(HttpExchange exchange, String origin) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET")) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        Session session = authentication.session(exchange);
        if (session == null) redirect(exchange, PATH + "/login");
        else send(exchange, 200, page(AdminSettingsPages.settingsPage(session.csrf()), true), "text/html; charset=utf-8");
    }

    private void login(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        if (!authentication.consumeLoginToken(form.get("csrf"))) {
            send(exchange, 403, loginPage("Sign-in expired. Reload the page and try again.", authentication.issueLoginToken()),
                "text/html; charset=utf-8");
            return;
        }
        String password = form.get("password");
        String client = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (authentication.throttled(client)) {
            auditOutcome("login", "admin", client, "throttled");
            exchange.getResponseHeaders().set("Retry-After", "300");
            send(exchange, 429, loginPage("Too many attempts. Try again in five minutes.", authentication.issueLoginToken()),
                "text/html; charset=utf-8");
            return;
        }
        if (!authentication.matchesPassword(password)) {
            authentication.failed(client);
            auditOutcome("login", "admin", client, "denied");
            send(exchange, 401, loginPage("Invalid password", authentication.issueLoginToken()), "text/html; charset=utf-8");
            return;
        }
        String token = authentication.signIn(client);
        exchange.getResponseHeaders().add("Set-Cookie", authentication.cookie(token, 1800));
        auditOutcome("login", "admin", client, "succeeded");
        redirect(exchange, PATH + "/overview");
    }

    private String actionErrorPage(String action, Map<String, String> form, String csrf,
                                   StoreException error) {
        try {
            if (action.equals("policy") && form.containsKey("bucket")) {
                String query = "name=" + AdminBrowser.encoded(form.get("bucket"));
                return page(AdminBrowser.bucket(store, query, csrf,
                    new AdminBrowser.EditorDraft(form.getOrDefault("policy", ""), error.getMessage()),
                    bucketActivity), true);
            }
            if (action.equals("edit-object") && form.containsKey("bucket") && form.containsKey("key")) {
                String query = "bucket=" + AdminBrowser.encoded(form.get("bucket")) +
                    "&key=" + AdminBrowser.encoded(form.get("key"));
                return page(AdminBrowser.object(store, query, csrf,
                    new AdminBrowser.EditorDraft(form.getOrDefault("content", ""), error.getMessage())), true);
            }
        } catch (IOException | IllegalArgumentException | StoreException ignored) {
        }
        return page(consoleHeader(csrf) +
            "<h2>Change was not saved</h2><p class=error>" + escape(error.getMessage()) +
            "</p><p><a href='" + PATH + "/buckets'>Return to buckets</a> · " +
            "<a href='" + PATH + "/credentials'>Return to access keys</a></p>");
    }

    private static String loginPage(String error, String csrf) {
        return page("<div class=login><div class=login-card><div class=login-brand>" +
            AdminLayout.logoMark() + "<strong>ObjectStore</strong></div><h1>Admin console</h1>" +
            "<p class=muted>Sign in to view this service.</p>" +
            (error.isEmpty() ? "" : "<p role=alert class=error>" + error + "</p>") +
            "<form method=post action='" + PATH + "/login'>" +
            "<input type=hidden name=csrf value='" + csrf + "'>" +
            "<label for=password>Admin password</label><input id=password name=password type=password " +
            "autocomplete=current-password required autofocus><button type=submit>Sign in</button>" +
            "</form></div></div>");
    }

    private static String auditTarget(Map<String, String> form) {
        String bucket = form.getOrDefault("bucket", "");
        String key = form.getOrDefault("key", "");
        String id = form.getOrDefault("id", "");
        if (!id.isEmpty()) return "key:" + id;
        if (!bucket.isEmpty()) return key.isEmpty() ? bucket : bucket + "/" + key;
        return "admin";
    }

    private void auditOutcome(String action, String target, String peer, String reason) {
        try { audit.record(action, target, reason, peer); }
        catch (IOException error) { System.err.println("Admin audit write failed: " + error.getMessage()); }
    }

    private void auditStart(String action, String target, HttpExchange exchange) throws IOException {
        audit.record(action, target, "started",
            exchange.getRemoteAddress().getAddress().getHostAddress());
    }

    private static Set<String> keyPermissions(Map<String, String> form) {
        Set<String> permissions = new java.util.HashSet<>();
        for (String permission : List.of("read", "write", "list"))
            if (form.containsKey(permission)) permissions.add(permission);
        return permissions;
    }

    static String page(String content, boolean widgets) {
        return AdminLayout.page(content, widgets);
    }

    private static String page(String content) {
        return AdminLayout.page(content);
    }

    static String consoleHeader(String csrf) {
        return AdminLayout.consoleHeader(csrf);
    }

    static String icon(String name) {
        return AdminLayout.icon(name);
    }

    static String escape(String value) {
        return AdminLayout.escape(value);
    }

    static String formatBytes(long bytes) {
        return AdminLayout.formatBytes(bytes);
    }
}
