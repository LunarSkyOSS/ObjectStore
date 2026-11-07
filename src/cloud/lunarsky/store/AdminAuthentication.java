package cloud.lunarsky.store;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
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
import static cloud.lunarsky.store.AdminConsole.PATH;

final class AdminAuthentication {
    private static final long SESSION_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final long ATTEMPT_WINDOW_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int MAX_SESSIONS = 128;
    private static final int MAX_CLIENTS = 1024;
    private final byte[] passwordHash;
    private final URI publicOrigin;
    private final SecureRandom random = new SecureRandom();
    private final LinkedHashMap<String, Session> sessions = new LinkedHashMap<>();
    private final LinkedHashMap<String, Long> loginTokens = new LinkedHashMap<>();
    private final LinkedHashMap<String, Attempt> attempts = new LinkedHashMap<>();

    private record Attempt(long started, int failures) { }
    record Session(long expires, String csrf) { }

    private AdminAuthentication(byte[] passwordHash, URI publicOrigin) {
        this.passwordHash = passwordHash;
        this.publicOrigin = publicOrigin;
    }

    static AdminAuthentication fromEnvironment(Map<String, String> env) throws IOException {
        return new AdminAuthentication(passwordHash(env.get("ADMIN_UI_PASSWORD_FILE")), configuredOrigin(env));
    }

    private static byte[] passwordHash(String file) throws IOException {
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
        return hash;
    }

    private static URI configuredOrigin(Map<String, String> env) {
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
        return origin;
    }

    boolean matchesPassword(String password) {
        byte[] candidate = SigV4.hash((password == null ? "" : password).getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(candidate, passwordHash);
    }

    synchronized String signIn(String client) {
        String token = randomToken();
        attempts.remove(client);
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue().expires() <= now);
        if (sessions.size() >= MAX_SESSIONS) sessions.remove(sessions.keySet().iterator().next());
        sessions.put(token, new Session(now + SESSION_MILLIS, randomToken()));
        return token;
    }

    synchronized void signOut(HttpExchange exchange) {
        sessions.remove(sessionCookie(exchange));
    }

    synchronized String issueLoginToken() {
        long now = System.currentTimeMillis();
        loginTokens.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (loginTokens.size() >= MAX_SESSIONS) loginTokens.remove(loginTokens.keySet().iterator().next());
        String token = randomToken();
        loginTokens.put(token, now + Duration.ofMinutes(10).toMillis());
        return token;
    }

    synchronized boolean consumeLoginToken(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return false;
        Long until = loginTokens.remove(token);
        return until != null && until > System.currentTimeMillis();
    }

    String randomToken() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    synchronized boolean throttled(String client) {
        Attempt attempt = attempts.get(client);
        return attempt != null && System.currentTimeMillis() - attempt.started < ATTEMPT_WINDOW_MILLIS &&
            attempt.failures >= 5;
    }

    synchronized void failed(String client) {
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

    synchronized Session session(HttpExchange exchange) {
        String token = sessionCookie(exchange);
        Session session = sessions.get(token);
        if (session == null) return null;
        if (session.expires() > System.currentTimeMillis()) return session;
        sessions.remove(token);
        return null;
    }

    String sessionCookie(HttpExchange exchange) {
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

    String cookie(String token, int seconds) {
        String name = secureOrigin() ? "__Host-objectstore_admin" : "objectstore_admin";
        String path = secureOrigin() ? "/" : PATH;
        return name + "=" + token + "; Path=" + path + "; HttpOnly; SameSite=Strict; Max-Age=" + seconds +
            (secureOrigin() ? "; Secure" : "");
    }

    boolean secureOrigin() {
        return publicOrigin != null && "https".equals(publicOrigin.getScheme());
    }

    static boolean privateIpv4(String host) {
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

    String requestOrigin(HttpExchange exchange) {
        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || exchange.getRequestHeaders().get("Host").size() != 1) return null;
        if (publicOrigin != null)
            return host.equalsIgnoreCase(publicOrigin.getRawAuthority()) ? publicOrigin.toString() : null;
        if (!host.matches("(?i)(localhost|127\\.0\\.0\\.1|\\[::1\\])(?::[0-9]{1,5})?")) return null;
        return "http://" + host.toLowerCase(java.util.Locale.ROOT);
    }

    static boolean adminOriginAllowed(HttpExchange exchange, String expected) {
        String submitted = exchange.getRequestHeaders().getFirst("Origin");
        if (submitted == null || submitted.equals(expected) || submitted.equals("null")) return true;
        return "same-origin".equals(exchange.getRequestHeaders().getFirst("Sec-Fetch-Site"));
    }
}
