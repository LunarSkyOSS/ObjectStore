package cloud.lunarsky.store;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

final class AdminBranding {
    static final String DEFAULT_NAME = "ObjectStore admin";
    static final String DEFAULT_THEME = "deep-dark";
    static final int MAX_ICON_BYTES = 128 * 1024;
    private static final int MAX_FILE_BYTES = 180 * 1024;
    private final Path file;
    private volatile State state;

    record State(String name, String mime, byte[] icon, String defaultTheme) { }

    AdminBranding(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        State loaded = new State(DEFAULT_NAME, "", new byte[0], DEFAULT_THEME);
        try {
            if (Files.exists(this.file)) loaded = decode(Files.readAllBytes(this.file));
        } catch (IOException error) {
            System.err.println("Could not load admin branding; using defaults: " + error.getMessage());
        }
        this.state = loaded;
    }

    State state() {
        State current = state;
        return new State(current.name(), current.mime(), current.icon().clone(), current.defaultTheme());
    }

    synchronized void setName(String name) throws IOException {
        String value = name == null ? "" : name.trim();
        if (value.isEmpty() || value.length() > 40 || value.chars().anyMatch(Character::isISOControl))
            throw new StoreException(400, "InvalidArgument", "Use a display name of 1 to 40 characters");
        persist(new State(value, state.mime(), state.icon(), state.defaultTheme()));
    }

    synchronized void setIcon(byte[] icon) throws IOException {
        if (icon.length == 0 || icon.length > MAX_ICON_BYTES)
            throw new StoreException(413, "InvalidArgument", "Icon must be at most 128 KiB");
        String mime = mime(icon);
        if (mime == null)
            throw new StoreException(415, "InvalidArgument", "Use a PNG, JPEG, or WebP icon");
        persist(new State(state.name(), mime, icon.clone(), state.defaultTheme()));
    }

    synchronized void setDefaultTheme(String theme) throws IOException {
        if (!validTheme(theme))
            throw new StoreException(400, "InvalidArgument", "Choose a supported Biscuit theme");
        persist(new State(state.name(), state.mime(), state.icon(), theme));
    }

    synchronized void reset() throws IOException {
        persist(new State(DEFAULT_NAME, "", new byte[0], state.defaultTheme()));
    }

    String json() {
        State current = state;
        String name = current.name().replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"name\":\"" + name + "\",\"icon\":\"" +
            (current.icon().length == 0 ? AdminConsole.PATH + "/logo.png" :
                AdminConsole.PATH + "/branding-icon?v=" +
                Integer.toHexString(java.util.Arrays.hashCode(current.icon()))) +
            "\",\"defaultTheme\":\"" + current.defaultTheme() + "\"}";
    }

    private void persist(State next) throws IOException {
        Path parent = file.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, "admin-branding-", ".part");
        try {
            byte[] bytes = ("ObjectStoreBranding 2\n" +
                Base64.getEncoder().encodeToString(next.name().getBytes(StandardCharsets.UTF_8)) + "\n" +
                next.mime() + "\n" + Base64.getEncoder().encodeToString(next.icon()) + "\n" +
                next.defaultTheme() + "\n")
                .getBytes(StandardCharsets.US_ASCII);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(parent, StandardOpenOption.READ)) {
                directory.force(true);
            }
            state = next;
        } finally { Files.deleteIfExists(temporary); }
    }

    private static State decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_FILE_BYTES) throw new IOException("Admin branding file is too large");
        try {
            String[] lines = new String(bytes, StandardCharsets.US_ASCII).split("\\n", -1);
            boolean oldFormat = lines.length == 5 && lines[0].equals("ObjectStoreBranding 1") &&
                lines[4].isEmpty();
            boolean newFormat = lines.length == 6 && lines[0].equals("ObjectStoreBranding 2") &&
                lines[5].isEmpty();
            if (!oldFormat && !newFormat)
                throw new IllegalArgumentException();
            String name = new String(Base64.getDecoder().decode(lines[1]), StandardCharsets.UTF_8);
            byte[] icon = Base64.getDecoder().decode(lines[3]);
            if (name.isBlank() || name.length() > 40 || name.chars().anyMatch(Character::isISOControl) ||
                icon.length > MAX_ICON_BYTES ||
                !(icon.length == 0 && lines[2].isEmpty() || lines[2].equals(mime(icon))))
                throw new IllegalArgumentException();
            String theme = oldFormat ? DEFAULT_THEME : lines[4];
            if (!validTheme(theme)) throw new IllegalArgumentException();
            return new State(name, lines[2], icon, theme);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid admin branding file", error);
        }
    }

    private static boolean validTheme(String theme) {
        return "auto".equals(theme) || "light".equals(theme) ||
            "dark".equals(theme) || "deep-dark".equals(theme);
    }

    private static String mime(byte[] bytes) {
        if (bytes.length >= 8 && (bytes[0] & 255) == 137 && bytes[1] == 80 && bytes[2] == 78 &&
            bytes[3] == 71 && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10)
            return "image/png";
        if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 &&
            (bytes[2] & 255) == 255) return "image/jpeg";
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' &&
            bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' &&
            bytes[11] == 'P') return "image/webp";
        return null;
    }
}
