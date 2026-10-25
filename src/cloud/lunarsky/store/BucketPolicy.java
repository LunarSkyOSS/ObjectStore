package cloud.lunarsky.store;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class BucketPolicy {
    private static final BucketPolicy EMPTY = new BucketPolicy(List.of());
    private record Statement(boolean allow, List<String> principals, List<String> actions,
                             List<String> resources) {
        boolean matches(String principal, String action, String resource) {
            return (principals.contains("*") || principal != null && principals.contains(principal)) &&
                actions.stream().anyMatch(pattern -> glob(pattern, action)) &&
                resources.stream().anyMatch(pattern -> glob(pattern, resource));
        }
    }

    private final List<Statement> statements;
    private final boolean publicAllow;

    private BucketPolicy(List<Statement> statements) {
        this.statements = List.copyOf(statements);
        this.publicAllow = statements.stream().anyMatch(
            statement -> statement.allow() && statement.principals().contains("*"));
    }

    static BucketPolicy empty() { return EMPTY; }

    boolean publicAllow() { return publicAllow; }

    int decide(String principal, String action, String resource) {
        boolean allowed = false;
        for (Statement statement : statements) {
            if (!statement.matches(principal, action, resource)) continue;
            if (!statement.allow()) return -1;
            allowed = true;
        }
        return allowed ? 1 : 0;
    }

    static BucketPolicy parse(String json, String bucket) {
        return parse(json, bucket, false);
    }

    static BucketPolicy parseIdentity(String json) {
        return parse(json, null, true);
    }

    private static BucketPolicy parse(String json, String bucket, boolean identity) {
        if (json == null || json.isBlank() || json.getBytes(StandardCharsets.UTF_8).length > 20480)
            throw invalid();
        Object parsed = new Parser(json).parse();
        Map<String, Object> policy = object(parsed);
        if (!policy.keySet().stream().allMatch(Set.of("Version", "Id", "Statement")::contains) ||
            !"2012-10-17".equals(policy.get("Version"))) throw invalid();
        Object raw = policy.get("Statement");
        if (raw == null) throw invalid();
        List<?> entries = raw instanceof List<?> list ? list : List.of(raw);
        if (entries.isEmpty() || entries.size() > 64) throw invalid();
        List<Statement> statements = new ArrayList<>();
        for (Object entry : entries) {
            Map<String, Object> item = object(entry);
            Set<String> keys = identity ? Set.of("Sid", "Effect", "Action", "Resource") :
                Set.of("Sid", "Effect", "Principal", "Action", "Resource");
            if (!item.keySet().stream().allMatch(keys::contains))
                throw invalid();
            boolean allow;
            if ("Allow".equals(item.get("Effect"))) allow = true;
            else if ("Deny".equals(item.get("Effect"))) allow = false;
            else throw invalid();
            Object principal = item.get("Principal");
            List<String> principals = identity ? List.of("*") : principal instanceof Map<?, ?> map
                ? strings(object(map).get("AWS")) : strings(principal);
            if (!identity && principal instanceof Map<?, ?> map && !map.keySet().equals(Set.of("AWS")))
                throw invalid();
            for (String name : principals)
                if (!name.equals("*") && !name.matches("[A-Za-z0-9]{16,128}")) throw invalid();
            List<String> actions = strings(item.get("Action"));
            for (String action : actions)
                if (!action.matches("s3:[A-Za-z*]+")) throw invalid();
            List<String> resources = strings(item.get("Resource"));
            String bucketArn = "arn:aws:s3:::" + bucket;
            for (String resource : resources)
                if (identity ? !resource.matches("arn:aws:s3:::[a-z0-9*?][a-z0-9*?.-]{0,62}(/[^\\p{Cntrl}]+)?") :
                    !resource.equals(bucketArn) && !resource.startsWith(bucketArn + "/")) throw invalid();
            statements.add(new Statement(allow, principals, actions, resources));
        }
        return new BucketPolicy(statements);
    }

    private static List<String> strings(Object value) {
        if (value == null) throw invalid();
        List<?> values = value instanceof List<?> list ? list : List.of(value);
        if (values.isEmpty() || values.size() > 64) throw invalid();
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank() || text.length() > 1024)
                throw invalid();
            result.add(text);
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw invalid();
        return (Map<String, Object>) value;
    }

    private static StoreException invalid() {
        return new StoreException(400, "MalformedPolicy", "Unsupported or malformed bucket policy");
    }

    private static boolean glob(String pattern, String text) {
        int p = 0, t = 0, star = -1, retry = 0;
        while (t < text.length()) {
            if (p < pattern.length() && pattern.charAt(p) == text.charAt(t)) { p++; t++; }
            else if (p < pattern.length() && pattern.charAt(p) == '*') { star = p++; retry = t; }
            else if (star >= 0) { p = star + 1; t = ++retry; }
            else return false;
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }

    private static final class Parser {
        private final String input;
        private int index;

        Parser(String input) { this.input = input; }

        Object parse() {
            Object value = value(0);
            space();
            if (index != input.length()) throw invalid();
            return value;
        }

        private Object value(int depth) {
            if (depth > 16) throw invalid();
            space();
            if (index == input.length()) throw invalid();
            return switch (input.charAt(index)) {
                case '"' -> string();
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                default -> throw invalid();
            };
        }

        private Map<String, Object> object(int depth) {
            index++;
            Map<String, Object> map = new LinkedHashMap<>();
            space();
            if (take('}')) return map;
            do {
                space();
                if (index == input.length() || input.charAt(index) != '"') throw invalid();
                String key = string();
                space();
                expect(':');
                if (map.putIfAbsent(key, value(depth)) != null) throw invalid();
                space();
                if (take('}')) return map;
                expect(',');
            } while (true);
        }

        private List<Object> array(int depth) {
            index++;
            List<Object> list = new ArrayList<>();
            space();
            if (take(']')) return list;
            do {
                if (list.size() >= 64) throw invalid();
                list.add(value(depth));
                space();
                if (take(']')) return list;
                expect(',');
            } while (true);
        }

        private String string() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (index < input.length()) {
                char c = input.charAt(index++);
                if (c == '"') return result.toString();
                if (c < 0x20) throw invalid();
                if (c != '\\') { result.append(c); continue; }
                if (index == input.length()) throw invalid();
                char escape = input.charAt(index++);
                switch (escape) {
                    case '"', '\\', '/' -> result.append(escape);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (index + 4 > input.length()) throw invalid();
                        try { result.append((char) Integer.parseInt(input.substring(index, index + 4), 16)); }
                        catch (NumberFormatException error) { throw invalid(); }
                        index += 4;
                    }
                    default -> throw invalid();
                }
            }
            throw invalid();
        }

        private void space() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) index++;
        }

        private boolean take(char expected) {
            if (index < input.length() && input.charAt(index) == expected) { index++; return true; }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw invalid();
        }
    }
}
