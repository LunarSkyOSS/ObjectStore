package cloud.lunarsky.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class CliTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("store-cli-test-");
        try {
            var versionOutput = new ByteArrayOutputStream();
            if (Cli.run(new String[]{"version"}, root, new PrintStream(versionOutput), System.err) != 0 ||
                !versionOutput.toString().contains(Version.VALUE)) throw new AssertionError("CLI version");
            byte[] payload = "verified".getBytes(StandardCharsets.UTF_8);
            try (var store = new DiskStore(root, 100, 1000)) {
                store.put("objects", "example", new ByteArrayInputStream(payload), payload.length,
                    SigV4.hex(SigV4.hash(payload)), null, false, "text/plain");
                var output = new ByteArrayOutputStream();
                int status = Cli.run(new String[]{"status"}, root, new PrintStream(output), System.err);
                if (status != 0 || !output.toString().contains("objects=1") ||
                    !output.toString().contains("payload_bytes=8")) throw new AssertionError("CLI status");
                output.reset();
                status = Cli.run(new String[]{"verify"}, root, new PrintStream(output), System.err);
                if (status != 0 || !output.toString().contains("verified_objects=1"))
                    throw new AssertionError("CLI verification");
            }
            String id = SigV4.hex(SigV4.hash("objects/example".getBytes(StandardCharsets.UTF_8)));
            Path file = root.resolve("objects").resolve(id.substring(0, 2)).resolve(id);
            int header;
            try (var input = new DataInputStream(Files.newInputStream(file))) {
                header = DiskStore.readRecord(input).headerLength();
            }
            try (var bytes = new RandomAccessFile(file.toFile(), "rw")) {
                bytes.seek(header);
                bytes.write('X');
            }
            var output = new ByteArrayOutputStream();
            int status = Cli.run(new String[]{"verify"}, root, new PrintStream(output), new PrintStream(output));
            if (status != 1 || !output.toString().contains("Object checksum mismatch"))
                throw new AssertionError("CLI missed corrupted payload");
            System.out.println("CLI tests passed: version, live status, verification, corruption exit code");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
