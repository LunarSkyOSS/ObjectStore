package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

final class AdminConsole {
    static final String PATH = "/_objectstore/admin";
    private static final String STYLE = """
        :root { color-scheme: dark; }
        body { background: #14121d; color: #f4f0fa; font: 16px system-ui; margin: 0;
               padding: clamp(20px, 4vw, 48px); }
        main { max-width: 1080px; margin: 0 auto; }
        .login { min-height: calc(100dvh - 2 * clamp(20px, 4vw, 48px));
                 display: grid; place-items: center; }
        .login-card { width: min(100%, 400px); box-sizing: border-box; padding: 34px;
                      border: 1px solid #514663; border-radius: 18px; background: #211c2b;
                      box-shadow: 0 24px 80px #08060d88; }
        .mark { display: grid; place-items: center; width: 48px; height: 48px; flex: none; }
        .mark img { width: 56px; height: 56px; max-width: none; }
        .login-brand { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; }
        .login-brand strong { font-size: 1.1rem; }
        .login-card h1 { font-size: 1.75rem; }
        .login-card .muted { margin-bottom: 28px; line-height: 1.5; }
        h1 { margin: 0; font-size: 2rem; }
        header { display: flex; justify-content: space-between; align-items: center; gap: 16px;
                 border-bottom: 1px solid #3e344e; padding-bottom: 22px; }
        .header-title { display: flex; align-items: center; gap: 14px; }
        .header-title h1 { font-size: 1.35rem; }
        .header-title small { margin-top: 3px; }
        .intro { display: flex; align-items: center; justify-content: space-between;
                 gap: 16px; margin: 34px 0 18px; }
        .intro h2, .buckets h2 { margin: 0 0 6px; font-size: 1.3rem; }
        .intro p, .buckets p { margin: 0; color: #aaa2bb; }
        .muted, small { color: #aaa2bb; }
        .error { color: #ffafa9; }
        form { margin-top: 24px; }
        label { display: block; margin-bottom: 8px; }
        input, button { font: inherit; border: 1px solid #514663; border-radius: 8px;
                        padding: 12px 14px; }
        input { display: block; width: min(100%, 360px); box-sizing: border-box;
                background: #211c2b; color: #fff; }
        input[type=hidden] { display: none; }
        .login input[type=password], .login button { width: 100%; }
        button { background: #b39add; color: #20172c; cursor: pointer; }
        form button { margin-top: 12px; }
        header form { margin: 0; }
        header button { background: #282232; color: #eee; }
        .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
                gap: 14px; }
        biscuit-cardview { display: block; --biscuit-cardview-background: #211c2b;
                           --biscuit-cardview-border: 1px solid #463a58;
                           --biscuit-cardview-radius: 14px;
                           --biscuit-cardview-padding: 22px;
                           --biscuit-cardview-color: #f4f0fa; }
        biscuit-cardview:not(:defined) { background: #211c2b; border: 1px solid #463a58;
                                        border-radius: 14px; padding: 22px; }
        biscuit-cardview small { display: block; margin-bottom: 12px; }
        biscuit-cardview strong { display: block; font-size: 1.2rem; overflow-wrap: anywhere; }
        biscuit-badgeview:not(:defined) { display: inline-block; padding: 6px 10px;
            border-radius: 999px; background: #342844; color: #d9c6f5; font-size: .8rem; }
        .buckets { margin-top: 34px; }
        .table-wrap { overflow: auto; margin-top: 18px; border: 1px solid #463a58;
                      border-radius: 14px; background: #211c2b; }
        table { width: 100%; border-collapse: collapse; text-align: left; }
        th, td { padding: 15px 18px; border-bottom: 1px solid #3b314b; white-space: nowrap; }
        tr:last-child td { border-bottom: 0; }
        th { color: #aaa2bb; font-size: .75rem; text-transform: uppercase; letter-spacing: .1em; }
        td { font-size: .93rem; }
        .empty { padding: 28px; color: #aaa2bb; }
        a { color: #c6a8f3; text-decoration: none; }
        a:hover { text-decoration: underline; }
        .crumbs { display: flex; flex-wrap: wrap; align-items: center; gap: 8px;
                  color: #aaa2bb; font-size: .9rem; margin: 30px 0 12px; }
        .crumbs span { overflow-wrap: anywhere; }
        .toolbar { display: flex; flex-wrap: wrap; align-items: end; justify-content: space-between;
                   gap: 16px; margin: 22px 0; }
        .toolbar form { display: flex; flex-wrap: wrap; align-items: end; gap: 8px; margin: 0; }
        .toolbar label { margin: 0; color: #aaa2bb; font-size: .85rem; }
        .toolbar input { min-width: min(280px, 100%); }
        .toolbar button { margin: 0; }
        .toolbar .field { display: grid; gap: 6px; }
        .button-link { display: inline-flex; align-items: center; justify-content: center;
                       border: 1px solid #514663; border-radius: 8px; padding: 12px 14px;
                       color: #f4f0fa; background: #282232; }
        .button-link:hover { background: #342a42; text-decoration: none; }
        .name-cell { max-width: 420px; white-space: normal; overflow-wrap: anywhere; }
        .kind { color: #aaa2bb; font-size: .8rem; }
        .pagination { display: flex; gap: 12px; align-items: center; margin-top: 18px; }
        .detail-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
                       gap: 14px; margin-top: 22px; }
        .detail-card { border: 1px solid #463a58; border-radius: 14px; background: #211c2b;
                       padding: 22px; min-width: 0; }
        .detail-card h3 { margin: 0 0 16px; font-size: 1rem; }
        .fact { display: flex; align-items: baseline; justify-content: space-between;
                gap: 16px; padding: 9px 0; border-top: 1px solid #3b314b; }
        .fact span { color: #aaa2bb; }
        .fact strong { text-align: right; overflow-wrap: anywhere; }
        .insight { margin-top: 26px; }
        .console-nav { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px 0;
                       border-bottom: 1px solid #3e344e; }
        .console-nav a { padding: 8px 12px; border-radius: 8px; color: #cfc5df; }
        .console-nav a:hover { background: #282232; text-decoration: none; }
        .perf-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr));
                     gap: 14px; margin-top: 24px; }
        .perf-grid biscuit-cardview strong { font-variant-numeric: tabular-nums; }
        .perf-charts { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr));
                       gap: 16px; margin-top: 24px; }
        .perf-charts[hidden] { display: none; }
        .perf-status { color: #aaa2bb; margin-top: 12px; min-height: 24px; }
        .perf-note { color: #aaa2bb; font-size: .9rem; line-height: 1.5; }
        biscuit-chartview { --biscuit-chart-surface: #211c2b;
                            --biscuit-chart-color: #f4f0fa;
                            --biscuit-chart-muted: #aaa2bb;
                            --biscuit-chart-border: #463a58;
                            --biscuit-chart-accent: #b39add; }
        details { margin-top: 22px; padding: 18px 22px; border: 1px solid #463a58;
                  border-radius: 14px; background: #211c2b; }
        summary { cursor: pointer; font-weight: 600; }
        pre { overflow-wrap: anywhere; white-space: pre-wrap; }
        @media (max-width: 820px) { .perf-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
        @media (max-width: 520px) {
            .login-card { padding: 26px; }
            .intro { align-items: start; }
            .grid > :last-child:nth-child(odd) { grid-column: 1 / -1; }
            .grid biscuit-cardview { --biscuit-cardview-padding: 16px; }
            .perf-grid biscuit-cardview { --biscuit-cardview-padding: 16px; }
            .perf-grid biscuit-cardview small { margin-bottom: 8px; }
            .perf-charts { grid-template-columns: minmax(0, 1fr); }
        }
        """;
    private static final long SESSION_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final long ATTEMPT_WINDOW_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int MAX_SESSIONS = 128;
    private static final int MAX_CLIENTS = 1024;
    private final ObjectStorage store;
    private final byte[] passwordHash;
    private final URI publicOrigin;
    private final String biscuitScript;
    private final String performanceScript;
    private final byte[] logo;
    private volatile RequestMetrics metrics;
    private final SecureRandom random = new SecureRandom();
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> loginTokens = new LinkedHashMap<>();
    private final LinkedHashMap<String, Attempt> attempts = new LinkedHashMap<>();

    private record Attempt(long started, int failures) { }
    private record Session(long expires, String csrf) { }

    private AdminConsole(ObjectStorage store, byte[] passwordHash, URI publicOrigin,
                         String biscuitScript, String performanceScript, byte[] logo) {
        this.store = store;
        this.passwordHash = passwordHash;
        this.publicOrigin = publicOrigin;
        this.biscuitScript = biscuitScript;
        this.performanceScript = performanceScript;
        this.logo = logo;
    }

    void setMetrics(RequestMetrics metrics) { this.metrics = metrics; }

    static AdminConsole fromEnvironment(Map<String, String> env, ObjectStorage store) throws IOException {
        String enabled = env.getOrDefault("ADMIN_UI_ENABLED", "false");
        if (enabled.equals("false")) return null;
        if (!enabled.equals("true")) throw new IllegalArgumentException("Invalid ADMIN_UI_ENABLED");
        String file = env.get("ADMIN_UI_PASSWORD_FILE");
        if (file == null || file.isBlank())
            throw new IllegalArgumentException("ADMIN_UI_PASSWORD_FILE is required when the admin UI is enabled");
        byte[] password = Files.readAllBytes(Path.of(file));
        int length = password.length;
        if (length > 0 && password[length - 1] == '\n') length--;
        if (length > 0 && password[length - 1] == '\r') length--;
        if (length < 16 || length > 1024)
            throw new IllegalArgumentException("Admin password must contain 16 to 1024 bytes");
        for (int i = 0; i < length; i++) {
            if (password[i] == '\n' || password[i] == '\r' || password[i] == 0)
                throw new IllegalArgumentException("Admin password must be a single line");
        }
        byte[] trimmed = java.util.Arrays.copyOf(password, length);
        byte[] hash = SigV4.hash(trimmed);
        java.util.Arrays.fill(trimmed, (byte) 0);
        java.util.Arrays.fill(password, (byte) 0);
        String privateOrigin = env.get("ADMIN_UI_PRIVATE_ORIGIN");
        String publicOrigin = env.get("ADMIN_UI_ORIGIN");
        if (privateOrigin != null && !privateOrigin.isBlank() &&
            publicOrigin != null && !publicOrigin.isBlank())
            throw new IllegalArgumentException("Configure only one admin UI origin");
        String configured = privateOrigin != null && !privateOrigin.isBlank() ? privateOrigin : publicOrigin;
        URI origin = null;
        if (configured != null && !configured.isBlank()) {
            try { origin = URI.create(configured); }
            catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("Invalid ADMIN_UI_ORIGIN", error);
            }
            boolean privateHttp = privateOrigin != null && !privateOrigin.isBlank();
            if (!(privateHttp ? "http".equals(origin.getScheme()) &&
                    privateIpv4(origin.getHost()) && origin.getPort() > 0 && origin.getPort() <= 65535 :
                    "https".equals(origin.getScheme()) && origin.getHost() != null) ||
                origin.getUserInfo() != null || origin.getRawQuery() != null || origin.getRawFragment() != null ||
                !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/")))
                throw new IllegalArgumentException(privateHttp ?
                    "ADMIN_UI_PRIVATE_ORIGIN must be an HTTP origin with a private IPv4 address and port" :
                    "ADMIN_UI_ORIGIN must be an HTTPS origin");
            origin = URI.create(origin.toString().replaceFirst("/$", ""));
        }
        return new AdminConsole(store, hash, origin,
            Files.readString(Path.of("admin-ui/biscuit.js")),
            Files.readString(Path.of("admin-ui/performance.js")),
            Files.readAllBytes(Path.of("admin-ui/objectstore-logo.png")));
    }

    void handle(HttpExchange exchange) throws IOException {
        securityHeaders(exchange);
        String origin = requestOrigin(exchange);
        if (origin == null) {
            send(exchange, 403, page("<h1>Admin console unavailable</h1>" +
                "<p>Open the local console at <code>http://127.0.0.1:&lt;port&gt;" + PATH +
                "/login</code>, or use the configured origin.</p>"), "text/html; charset=utf-8");
            return;
        }
        String path = exchange.getRequestURI().getRawPath();
        String method = exchange.getRequestMethod();
        if (path.equals(PATH) || path.equals(PATH + "/")) {
            if (!method.equals("GET")) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            if (session(exchange) != null) redirect(exchange, PATH + "/overview");
            else redirect(exchange, PATH + "/login");
            return;
        }
        if (path.equals(PATH + "/login")) {
            if (method.equals("GET")) {
                send(exchange, 200, loginPage("", issueLoginToken()), "text/html; charset=utf-8");
                return;
            }
            if (method.equals("POST")) {
                login(exchange);
                return;
            }
            methodNotAllowed(exchange, "GET, POST");
            return;
        }
        if (path.equals(PATH + "/style.css")) {
            if (!method.equals("GET")) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            send(exchange, 200, STYLE, "text/css; charset=utf-8");
            return;
        }
        if (path.equals(PATH + "/logo.png")) {
            if (!method.equals("GET")) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, logo.length);
            exchange.getResponseBody().write(logo);
            return;
        }
        if (path.equals(PATH + "/biscuit.js")) {
            if (!method.equals("GET")) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            if (session(exchange) == null) {
                redirect(exchange, PATH + "/login");
                return;
            }
            send(exchange, 200, biscuitScript, "text/javascript; charset=utf-8");
            return;
        }
        if (path.equals(PATH + "/performance.js")) {
            if (!method.equals("GET")) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            if (session(exchange) == null) {
                redirect(exchange, PATH + "/login");
                return;
            }
            send(exchange, 200, performanceScript, "text/javascript; charset=utf-8");
            return;
        }
        if (path.equals(PATH + "/performance.json")) {
            if (!method.equals("GET")) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            if (session(exchange) == null) {
                redirect(exchange, PATH + "/login");
                return;
            }
            if (metrics == null) {
                send(exchange, 503, "Metrics unavailable", "text/plain; charset=utf-8");
                return;
            }
            send(exchange, 200, AdminPerformance.snapshot(metrics), "application/json; charset=utf-8");
            return;
        }
        if (path.equals(PATH + "/logout")) {
            if (!method.equals("POST")) {
                methodNotAllowed(exchange, "POST");
                return;
            }
            Session session = session(exchange);
            Map<String, String> form = readForm(exchange);
            if (session == null || !MessageDigest.isEqual(session.csrf().getBytes(StandardCharsets.US_ASCII),
                form.getOrDefault("csrf", "").getBytes(StandardCharsets.US_ASCII))) {
                send(exchange, 403, "Invalid sign-out request", "text/plain; charset=utf-8");
                return;
            }
            String token = sessionCookie(exchange);
            synchronized (this) {
                sessions.remove(token);
            }
            exchange.getResponseHeaders().add("Set-Cookie", cookie("", 0));
            redirect(exchange, PATH + "/login");
            return;
        }
        if (path.equals(PATH + "/overview")) {
            if (!method.equals("GET")) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            Session session = session(exchange);
            if (session == null) {
                redirect(exchange, PATH + "/login");
                return;
            }
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
            send(exchange, 200, overviewPage(mode, status, limits.maxObjectBytes(), limits.maxTotalBytes(),
                buckets, bucketListAvailable, session.csrf()),
                "text/html; charset=utf-8");
            return;
        }
        if (path.equals(PATH + "/performance")) {
            if (!method.equals("GET")) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            Session session = session(exchange);
            if (session == null) {
                redirect(exchange, PATH + "/login");
                return;
            }
            send(exchange, 200, performancePage(session.csrf()), "text/html; charset=utf-8");
            return;
        }
        if (path.equals(PATH + "/buckets") || path.equals(PATH + "/bucket") ||
            path.equals(PATH + "/object")) {
            if (!method.equals("GET")) {
                methodNotAllowed(exchange, "GET");
                return;
            }
            Session session = session(exchange);
            if (session == null) {
                redirect(exchange, PATH + "/login");
                return;
            }
            try {
                String body = path.equals(PATH + "/buckets") ?
                    AdminBrowser.index(store, exchange.getRequestURI().getRawQuery(), session.csrf()) :
                    path.equals(PATH + "/bucket") ?
                    AdminBrowser.bucket(store, exchange.getRequestURI().getRawQuery(), session.csrf()) :
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
            return;
        }
        send(exchange, 404, "Not found", "text/plain; charset=utf-8");
    }

    private void login(HttpExchange exchange) throws IOException {
        Map<String, String> form = readForm(exchange);
        if (!consumeLoginToken(form.get("csrf"))) {
            send(exchange, 403, loginPage("Sign-in expired. Reload the page and try again.", issueLoginToken()),
                "text/html; charset=utf-8");
            return;
        }
        String password = form.get("password");
        String client = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (throttled(client)) {
            exchange.getResponseHeaders().set("Retry-After", "300");
            send(exchange, 429, loginPage("Too many attempts. Try again in five minutes.", issueLoginToken()),
                "text/html; charset=utf-8");
            return;
        }
        byte[] candidate = SigV4.hash((password == null ? "" : password).getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(candidate, passwordHash)) {
            failed(client);
            send(exchange, 401, loginPage("Invalid password", issueLoginToken()), "text/html; charset=utf-8");
            return;
        }
        String token = randomToken();
        synchronized (this) {
            attempts.remove(client);
            long now = System.currentTimeMillis();
            sessions.entrySet().removeIf(entry -> entry.getValue().expires() <= now);
            if (sessions.size() >= MAX_SESSIONS) sessions.remove(sessions.keySet().iterator().next());
            sessions.put(token, new Session(now + SESSION_MILLIS, randomToken()));
        }
        exchange.getResponseHeaders().add("Set-Cookie", cookie(token, 1800));
        redirect(exchange, PATH + "/overview");
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].trim()
            .equalsIgnoreCase("application/x-www-form-urlencoded")) {
            throw new StoreException(415, "InvalidRequest", "Expected a form submission");
        }
        byte[] body = exchange.getRequestBody().readNBytes(4097);
        if (body.length > 4096) {
            throw new StoreException(413, "InvalidRequest", "Form is too large");
        }
        Map<String, String> values = new java.util.HashMap<>();
        try {
            for (String field : new String(body, StandardCharsets.UTF_8).split("&", -1)) {
                int split = field.indexOf('=');
                if (split < 0) continue;
                String key = URLDecoder.decode(field.substring(0, split), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(field.substring(split + 1), StandardCharsets.UTF_8);
                if (values.putIfAbsent(key, value) != null)
                    throw new IllegalArgumentException("Duplicate form field");
            }
        } catch (IllegalArgumentException error) {
            throw new StoreException(400, "InvalidRequest", "Invalid form submission");
        }
        return values;
    }

    private synchronized String issueLoginToken() {
        long now = System.currentTimeMillis();
        loginTokens.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (loginTokens.size() >= MAX_SESSIONS) loginTokens.remove(loginTokens.keySet().iterator().next());
        String token = randomToken();
        loginTokens.put(token, now + Duration.ofMinutes(10).toMillis());
        return token;
    }

    private synchronized boolean consumeLoginToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        Long until = loginTokens.remove(token);
        return until != null && until > System.currentTimeMillis();
    }

    private String randomToken() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private synchronized boolean throttled(String client) {
        Attempt attempt = attempts.get(client);
        return attempt != null && System.currentTimeMillis() - attempt.started < ATTEMPT_WINDOW_MILLIS &&
            attempt.failures >= 5;
    }

    private synchronized void failed(String client) {
        long now = System.currentTimeMillis();
        Attempt attempt = attempts.get(client);
        if (attempt == null || now - attempt.started >= ATTEMPT_WINDOW_MILLIS)
            attempts.put(client, new Attempt(now, 1));
        else attempts.put(client, new Attempt(attempt.started, attempt.failures + 1));
        if (attempts.size() > MAX_CLIENTS) {
            Iterator<String> oldest = attempts.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private synchronized Session session(HttpExchange exchange) {
        String token = sessionCookie(exchange);
        Session session = sessions.get(token);
        if (session == null) return null;
        if (session.expires() > System.currentTimeMillis()) return session;
        sessions.remove(token);
        return null;
    }

    private String sessionCookie(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        if (header == null || header.length() > 8192) return "";
        String name = secureOrigin() ? "__Host-objectstore_admin=" : "objectstore_admin=";
        for (String field : header.split(";")) {
            String value = field.trim();
            if (value.startsWith(name) && value.substring(name.length()).matches("[A-Za-z0-9_-]{43}"))
                return value.substring(name.length());
        }
        return "";
    }

    private String cookie(String token, int seconds) {
        String name = secureOrigin() ? "__Host-objectstore_admin" : "objectstore_admin";
        String path = secureOrigin() ? "/" : PATH;
        return name + "=" + token + "; Path=" + path + "; HttpOnly; SameSite=Strict; Max-Age=" + seconds +
            (secureOrigin() ? "; Secure" : "");
    }

    private boolean secureOrigin() {
        return publicOrigin != null && "https".equals(publicOrigin.getScheme());
    }

    private static boolean privateIpv4(String host) {
        if (host == null || !host.matches("[0-9]+(?:\\.[0-9]+){3}")) return false;
        String[] fields = host.split("\\.");
        int[] octets = new int[4];
        for (int i = 0; i < octets.length; i++) {
            if (fields[i].length() > 3 || (fields[i].length() > 1 && fields[i].startsWith("0"))) return false;
            octets[i] = Integer.parseInt(fields[i]);
            if (octets[i] > 255) return false;
        }
        return octets[0] == 10 ||
            (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) ||
            (octets[0] == 192 && octets[1] == 168);
    }

    private String requestOrigin(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || exchange.getRequestHeaders().get("Host").size() != 1) return null;
        if (publicOrigin != null)
            return host.equalsIgnoreCase(publicOrigin.getRawAuthority()) ? publicOrigin.toString() : null;
        if (!host.matches("(?i)(localhost|127\\.0\\.0\\.1|\\[::1\\])(?::[0-9]{1,5})?")) return null;
        return "http://" + host.toLowerCase(java.util.Locale.ROOT);
    }

    private static void securityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Security-Policy",
            "default-src 'none'; style-src 'self'; style-src-elem 'self' 'unsafe-inline'; " +
            "style-src-attr 'none'; script-src 'self'; img-src 'self'; connect-src 'self'; form-action 'self'; " +
            "frame-ancestors 'none'; base-uri 'none'");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    }

    private static void methodNotAllowed(HttpExchange exchange, String methods) throws IOException {
        exchange.getResponseHeaders().set("Allow", methods);
        send(exchange, 405, "Method not allowed", "text/plain; charset=utf-8");
    }

    private static void redirect(HttpExchange exchange, String path) throws IOException {
        exchange.getResponseHeaders().set("Location", path);
        exchange.sendResponseHeaders(303, -1);
    }

    private static void send(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String loginPage(String error, String csrf) {
        return page("<div class=login><div class=login-card><div class=login-brand>" +
            logoMark() + "<strong>ObjectStore</strong></div><h1>Admin console</h1>" +
            "<p class=muted>Sign in to view this service.</p>" +
            (error.isEmpty() ? "" : "<p role=alert class=error>" + error + "</p>") +
            "<form method=post action='" + PATH + "/login'>" +
            "<input type=hidden name=csrf value='" + csrf + "'>" +
            "<label for=password>Admin password</label><input id=password name=password type=password " +
            "autocomplete=current-password required autofocus><button type=submit>Sign in</button>" +
            "</form></div></div>");
    }

    private static String overviewPage(String mode, String status, long maxObject, long maxTotal,
                                       List<ObjectStorage.Bucket> buckets, boolean bucketListAvailable,
                                       String csrf) {
        StringBuilder content = new StringBuilder(consoleHeader(csrf) +
            "<div class=intro><div><h2>Overview</h2><p>Service status and configured limits</p></div>" +
            "<biscuit-badgeview theme=dark variant='" + (status.equals("Ready") ? "success" : "danger") + "'>" +
            status + "</biscuit-badgeview></div>" +
            "<div class=grid><biscuit-cardview><small>Storage mode</small><strong>" + mode +
            "</strong></biscuit-cardview><biscuit-cardview><small>Buckets</small><strong>" +
            (bucketListAvailable ? buckets.size() : "Unavailable") +
            "</strong></biscuit-cardview><biscuit-cardview><small>Maximum object</small><strong>" +
            formatBytes(maxObject) + "</strong></biscuit-cardview><biscuit-cardview>" +
            "<small>Total storage limit</small><strong>" + formatBytes(maxTotal) +
            "</strong></biscuit-cardview></div><section class=buckets><h2>Buckets</h2>" +
            "<p>Read-only view of the first 25 buckets. <a href='" + PATH +
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

    private static String performancePage(String csrf) {
        StringBuilder content = new StringBuilder(consoleHeader(csrf));
        content.append("<div class=intro><div><h2>Performance</h2>")
            .append("<p>Live S3 traffic and process health</p></div>")
            .append("<biscuit-badgeview theme=dark variant=accent>Live</biscuit-badgeview></div>")
            .append("<p class=perf-note>Sampled every 3 seconds while this tab is visible. ")
            .append("Traffic excludes admin pages and health checks. Charts keep only the last 60 samples ")
            .append("in this browser tab.</p><p id=perf-state class=perf-status role=status>Collecting samples…</p>")
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
            .append("<biscuit-chartview id=perf-requests-chart theme=dark type=line ")
            .append("name='Requests / second' height=250 max-rows=60></biscuit-chartview>")
            .append("<biscuit-chartview id=perf-transfer-chart theme=dark type=line ")
            .append("name='Transfer / second' height=250 max-rows=60></biscuit-chartview>")
            .append("<biscuit-chartview id=perf-cpu-chart theme=dark type=line ")
            .append("name='Process CPU' height=250 max-rows=60></biscuit-chartview>")
            .append("<biscuit-chartview id=perf-memory-chart theme=dark type=line ")
            .append("name='Process memory' height=250 max-rows=60></biscuit-chartview>")
            .append("</div><p class=perf-note>Inbound and outbound measure HTTP body bytes, including ")
            .append("S3 metadata responses. CPU is the process share of available cores. ")
            .append("GC counts reset when the service restarts.</p>");
        return page(content.toString(), true, true);
    }

    private static String perfCard(String label, String id) {
        return "<biscuit-cardview><small>" + label + "</small><strong id='" + id +
            "'>—</strong></biscuit-cardview>";
    }

    private static String page(String content) {
        return page(content, false);
    }

    static String page(String content, boolean widgets) {
        return page(content, widgets, false);
    }

    private static String page(String content, boolean widgets, boolean performance) {
        return "<!doctype html><html lang=en><head><meta charset=utf-8>" +
            "<meta name=viewport content='width=device-width,initial-scale=1'><title>ObjectStore admin</title>" +
            "<link rel=stylesheet href='" + PATH + "/style.css'>" +
            (widgets ? "<script defer src='" + PATH + "/biscuit.js'></script>" : "") +
            (performance ? "<script defer src='" + PATH + "/performance.js'></script>" : "") +
            "</head><body><main>" +
            content + "</main></body></html>";
    }

    static String consoleHeader(String csrf) {
        return "<header><div class=header-title>" + logoMark() +
            "<div><h1>ObjectStore</h1><small>Admin console · v" + Version.VALUE +
            "</small></div></div><form method=post action='" + PATH +
            "/logout'><input type=hidden name=csrf value='" + csrf +
            "'><button type=submit>Sign out</button></form></header>" +
            "<nav class=console-nav aria-label='Admin sections'><a href='" + PATH +
            "/overview'>Overview</a><a href='" + PATH + "/buckets'>Buckets</a>" +
            "<a href='" + PATH + "/performance'>Performance</a></nav>";
    }

    private static String logoMark() {
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
