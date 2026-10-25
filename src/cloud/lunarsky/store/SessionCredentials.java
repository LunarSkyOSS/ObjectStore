package cloud.lunarsky.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

final class SessionCredentials {
    record Session(String secret, String token, Instant expires) { }

    private SessionCredentials() { }

    static Map<String, Session> fromEnvironment(Map<String, String> env, java.util.Set<String> permanent)
        throws IOException {
        String file = env.get("S3_SESSION_CREDENTIALS_FILE");
        if (file == null || file.isBlank()) return Map.of();
        var lines = Files.readAllLines(Path.of(file), StandardCharsets.UTF_8);
        if (lines.size() > 256) throw new IllegalArgumentException("Too many session credentials");
        Map<String, Session> sessions = new HashMap<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\\t", -1);
            if (fields.length != 4 || !fields[0].matches("[A-Za-z0-9]{16,128}") ||
                permanent.contains(fields[0]) || fields[1].length() < 32 ||
                fields[2].length() < 32 || fields[2].length() > 2048 ||
                fields[2].indexOf('\n') >= 0 || fields[2].indexOf('\r') >= 0)
                throw new IllegalArgumentException("Invalid session credential entry");
            Instant expires;
            try { expires = Instant.ofEpochSecond(Long.parseLong(fields[3])); }
            catch (RuntimeException error) { throw new IllegalArgumentException("Invalid session expiry", error); }
            if (sessions.putIfAbsent(fields[0], new Session(fields[1], fields[2], expires)) != null)
                throw new IllegalArgumentException("Duplicate session credential");
        }
        return Map.copyOf(sessions);
    }
}
