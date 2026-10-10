package cloud.lunarsky.objectstore.client;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private Json() { }

    static Object parse(byte[] bytes) throws ProtocolException {
        String source;
        try { source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new ProtocolException("Capability JSON is not UTF-8", e); }
        Parser parser = new Parser(source);
        Object value = parser.value(0);
        parser.space();
        if (parser.index != source.length()) throw new ProtocolException("Trailing capability JSON data");
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value, String name) throws ProtocolException {
        if (!(value instanceof Map<?, ?>)) throw new ProtocolException(name + " must be an object");
        return (Map<String, Object>) value;
    }

    static List<?> array(Object value, String name) throws ProtocolException {
        if (!(value instanceof List<?> list)) throw new ProtocolException(name + " must be an array");
        return list;
    }

    static String string(Object value, String name) throws ProtocolException {
        if (!(value instanceof String text) || text.isBlank())
            throw new ProtocolException(name + " must be a nonempty string");
        return text;
    }

    static long integer(Object value, String name) throws ProtocolException {
        if (!(value instanceof BigDecimal number)) throw new ProtocolException(name + " must be an integer");
        try { return number.longValueExact(); }
        catch (ArithmeticException e) { throw new ProtocolException(name + " is out of range", e); }
    }

    private static final class Parser {
        private final String source;
        private int index;

        private Parser(String source) { this.source = source; }

        private void space() {
            while (index < source.length() && (source.charAt(index) == ' ' || source.charAt(index) == '\n' ||
                source.charAt(index) == '\r' || source.charAt(index) == '\t')) index++;
        }

        private Object value(int depth) throws ProtocolException {
            if (depth > 16) throw new ProtocolException("Capability JSON is too deeply nested");
            space();
            if (index >= source.length()) throw new ProtocolException("Incomplete capability JSON");
            return switch (source.charAt(index)) {
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) throws ProtocolException {
            index++;
            space();
            Map<String, Object> result = new HashMap<>();
            if (take('}')) return result;
            do {
                space();
                if (index >= source.length() || source.charAt(index) != '"')
                    throw new ProtocolException("Capability JSON object key is missing");
                String key = string();
                space();
                require(':');
                if (result.containsKey(key)) throw new ProtocolException("Duplicate capability JSON key");
                result.put(key, value(depth));
                if (result.size() > 256) throw new ProtocolException("Capability JSON object is too large");
                space();
                if (take('}')) return result;
                require(',');
            } while (true);
        }

        private List<Object> array(int depth) throws ProtocolException {
            index++;
            space();
            List<Object> result = new ArrayList<>();
            if (take(']')) return result;
            do {
                result.add(value(depth));
                if (result.size() > 2048) throw new ProtocolException("Capability JSON array is too large");
                space();
                if (take(']')) return result;
                require(',');
            } while (true);
        }

        private String string() throws ProtocolException {
            index++;
            StringBuilder result = new StringBuilder();
            while (index < source.length()) {
                char current = source.charAt(index++);
                if (current == '"') {
                    for (int i = 0; i < result.length(); i++) {
                        char unit = result.charAt(i);
                        if (Character.isHighSurrogate(unit)) {
                            if (++i >= result.length() || !Character.isLowSurrogate(result.charAt(i)))
                                throw new ProtocolException("Invalid JSON Unicode surrogate");
                        } else if (Character.isLowSurrogate(unit))
                            throw new ProtocolException("Invalid JSON Unicode surrogate");
                    }
                    return result.toString();
                }
                if (current < 0x20) throw new ProtocolException("Unescaped control character in JSON string");
                if (current != '\\') {
                    result.append(current);
                    continue;
                }
                if (index >= source.length()) throw new ProtocolException("Incomplete JSON escape");
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (index + 4 > source.length()) throw new ProtocolException("Incomplete Unicode escape");
                        int unit = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(source.charAt(index++), 16);
                            if (digit < 0) throw new ProtocolException("Invalid Unicode escape");
                            unit = unit * 16 + digit;
                        }
                        result.append((char) unit);
                    }
                    default -> throw new ProtocolException("Invalid JSON escape");
                }
            }
            throw new ProtocolException("Unterminated JSON string");
        }

        private Object literal(String expected, Object value) throws ProtocolException {
            if (!source.startsWith(expected, index)) throw new ProtocolException("Invalid JSON literal");
            index += expected.length();
            return value;
        }

        private BigDecimal number() throws ProtocolException {
            int start = index;
            if (take('-') && index >= source.length()) throw new ProtocolException("Invalid JSON number");
            integerDigits();
            if (take('.')) requireDigits("Invalid JSON fraction");
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                requireDigits("Invalid JSON exponent");
            }
            try { return new BigDecimal(source.substring(start, index)); }
            catch (NumberFormatException e) { throw new ProtocolException("Invalid JSON number", e); }
        }

        private void integerDigits() throws ProtocolException {
            if (take('0')) {
                if (index < source.length() && Character.isDigit(source.charAt(index)))
                    throw new ProtocolException("Invalid JSON number");
            } else {
                if (index >= source.length() || source.charAt(index) < '1' || source.charAt(index) > '9')
                    throw new ProtocolException("Invalid JSON number");
                scanDigits();
            }
        }

        private void requireDigits(String message) throws ProtocolException {
            int first = index;
            scanDigits();
            if (first == index) throw new ProtocolException(message);
        }

        private void scanDigits() {
            while (index < source.length() && source.charAt(index) >= '0' && source.charAt(index) <= '9') index++;
        }

        private boolean take(char value) {
            if (index < source.length() && source.charAt(index) == value) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char value) throws ProtocolException {
            if (!take(value)) throw new ProtocolException("Expected '" + value + "' in capability JSON");
        }
    }
}
