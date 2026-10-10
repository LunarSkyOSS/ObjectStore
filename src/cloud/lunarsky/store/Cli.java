package cloud.lunarsky.store;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class Cli {
    private Cli() {}

    static final class Report {
        long objects, legacyObjects, payloadBytes, recordBytes, uploads, stagedBytes, checked, changedDuringScan;
        long errors;
        final List<String> problems = new ArrayList<>();

        void problem(String message) {
            errors++;
            if (problems.size() < 100) problems.add(message);
        }
    }

    public static void main(String[] args) {
        int result = run(args, Path.of(System.getenv().getOrDefault("DATA_DIR", "/data")), System.out, System.err);
        if (result != 0) System.exit(result);
    }

    static int run(String[] args, Path root, PrintStream out, PrintStream err) {
        if (args.length == 1 && (args[0].equals("version") || args[0].equals("--version"))) {
            out.println("ObjectStore " + Version.VALUE);
            return 0;
        }
        if (args.length == 1 && (args[0].equals("help") || args[0].equals("--help"))) {
            out.println("Usage: objectstore status|verify|version");
            out.println("status  Show stored object and multipart usage");
            out.println("verify  Check object records, paths and payload checksums");
            out.println("version Show the ObjectStore version");
            return 0;
        }
        if (args.length != 1 || !(args[0].equals("status") || args[0].equals("verify"))) {
            err.println("Usage: objectstore status|verify|version");
            return 2;
        }
        try {
            boolean verify = args[0].equals("verify");
            Report report = inspect(root, verify);
            out.println("version=" + Version.VALUE);
            out.println("objects=" + report.objects);
            out.println("legacy_objects=" + report.legacyObjects);
            out.println("payload_bytes=" + report.payloadBytes);
            out.println("record_bytes=" + report.recordBytes);
            out.println("multipart_uploads=" + report.uploads);
            out.println("multipart_staged_bytes=" + report.stagedBytes);
            if (verify) out.println("verified_objects=" + report.checked);
            if (report.changedDuringScan > 0) out.println("changed_during_scan=" + report.changedDuringScan);
            out.println("errors=" + report.errors);
            for (String problem : report.problems) err.println(problem);
            if (report.errors > report.problems.size())
                err.println((report.errors - report.problems.size()) + " further errors omitted");
            return report.errors == 0 ? 0 : 1;
        } catch (IOException error) {
            err.println("ObjectStore inspection failed: " + error.getMessage());
            return 1;
        }
    }

    static Report inspect(Path root, boolean verify) throws IOException {
        Path objects = root.resolve("objects");
        if (!Files.isDirectory(objects)) throw new IOException("Object data directory does not exist: " + objects);
        Report report = new Report();
        try (var paths = Files.walk(objects)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) inspectObject(objects, path, verify, report);
        }
        Path versions = root.resolve("versions");
        if (Files.isDirectory(versions)) {
            try (var paths = Files.walk(versions)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    if (!path.getFileName().toString().equals("manifest"))
                        inspectObject(versions, path, verify, report);
                }
            }
        }
        Path multipart = root.resolve("multipart");
        if (Files.isDirectory(multipart)) {
            try (var uploads = Files.list(multipart)) {
                for (Path dir : uploads.toList()) inspectUpload(dir, report);
            }
        }
        return report;
    }

    private static void inspectObject(Path objects, Path path, boolean verify, Report report) {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ);
             var input = new DataInputStream(Channels.newInputStream(channel))) {
            long size = channel.size();
            var record = DiskStore.readRecord(input);
            var meta = record.metadata();
            report.objects++;
            report.payloadBytes += meta.length();
            report.recordBytes += size;
            if (meta.key() == null) report.legacyObjects++;
            else {
                String id = SigV4.hex(SigV4.hash((meta.bucket() + "/" + meta.key()).getBytes(StandardCharsets.UTF_8)));
                Path expected = objects.getFileName().toString().equals("versions")
                    ? objects.resolve(id.substring(0, 2)).resolve(id).resolve(path.getFileName())
                    : objects.resolve(id.substring(0, 2)).resolve(id);
                if (!path.equals(expected)) report.problem("Mismatched object path: " + path);
            }
            if (size - record.headerLength() != meta.length()) {
                report.problem("Invalid object length: " + path);
                return;
            }
            if (verify) {
                MessageDigest sha = digest("SHA-256"), md5 = digest("MD5");
                byte[] buffer = new byte[65536];
                long count = 0;
                int n;
                while ((n = input.read(buffer)) != -1) {
                    count += n;
                    sha.update(buffer, 0, n);
                    md5.update(buffer, 0, n);
                }
                report.checked++;
                if (count != meta.length() || !MessageDigest.isEqual(sha.digest(), meta.sha256()) ||
                    !SigV4.hex(md5.digest()).equals(meta.etag()))
                    report.problem("Object checksum mismatch: " + path);
            }
        } catch (NoSuchFileException error) {
            report.changedDuringScan++;
        } catch (IOException | RuntimeException error) {
            report.problem("Unreadable object record: " + path + " (" + error.getClass().getSimpleName() + ")");
        }
    }

    private static void inspectUpload(Path dir, Report report) throws IOException {
        if (!Files.isDirectory(dir)) {
            report.problem("Unexpected multipart entry: " + dir);
            return;
        }
        report.uploads++;
        if (!Files.isRegularFile(dir.resolve("manifest"))) report.problem("Missing multipart manifest: " + dir);
        try (var files = Files.list(dir)) {
            for (Path path : files.toList()) {
                String name = path.getFileName().toString();
                if (name.matches("part-[0-9]{5}")) {
                    try { report.stagedBytes += Files.size(path); }
                    catch (NoSuchFileException error) { report.changedDuringScan++; }
                } else if (!name.equals("manifest")) report.problem("Unexpected multipart file: " + path);
            }
        } catch (NoSuchFileException error) {
            report.changedDuringScan++;
        }
    }

    private static MessageDigest digest(String name) {
        try { return MessageDigest.getInstance(name); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
