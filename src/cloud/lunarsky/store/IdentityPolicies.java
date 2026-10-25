package cloud.lunarsky.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

final class IdentityPolicies {
    private IdentityPolicies() { }

    static Map<String, BucketPolicy> fromEnvironment(Map<String, String> env, String owner,
                                                      Set<String> identities)
        throws IOException {
        String configured = env.get("S3_IDENTITY_POLICIES_DIR");
        if (configured == null || configured.isBlank()) return Map.of();
        Map<String, BucketPolicy> policies = new HashMap<>();
        try (var files = Files.newDirectoryStream(Path.of(configured), "*.json")) {
            for (Path path : files) {
                String name = path.getFileName().toString();
                String key = name.substring(0, name.length() - 5);
                if (!identities.contains(key) || key.equals(owner) || Files.isSymbolicLink(path) ||
                    Files.size(path) > 20480 || policies.size() >= 256)
                    throw new IllegalArgumentException("Invalid identity policy file");
                String json = Files.readString(path, StandardCharsets.UTF_8);
                policies.put(key, BucketPolicy.parseIdentity(json));
            }
        }
        return Map.copyOf(policies);
    }
}
