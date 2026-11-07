package cloud.lunarsky.store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;

public final class BucketActivityTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("bucket-activity-test-");
        Path file = directory.resolve("activity.tsv");
        try {
            try (BucketActivity activity = new BucketActivity(file)) {
                activity.record("first-bucket", "GET");
                activity.record("first-bucket", "HEAD");
                activity.record("first-bucket", "PUT");
                activity.record("first-bucket", "OPTIONS");
                activity.record("invalid/bucket", "PUT");
                var today = activity.days("first-bucket").getLast();
                if (!today.date().equals(LocalDate.now(ZoneOffset.UTC)) ||
                    today.reads() != 2 || today.writes() != 1)
                    throw new AssertionError("Daily requests were counted incorrectly");
                activity.rename("first-bucket", "second-bucket");
                if (activity.days("first-bucket").getLast().total() != 0 ||
                    activity.days("second-bucket").getLast().total() != 3)
                    throw new AssertionError("Rename lost bucket activity");
            }
            try (BucketActivity reloaded = new BucketActivity(file)) {
                if (reloaded.days("second-bucket").getLast().total() != 3)
                    throw new AssertionError("Bucket activity did not survive a restart");
                reloaded.remove("second-bucket");
            }
            try (BucketActivity reloaded = new BucketActivity(file)) {
                if (reloaded.days("second-bucket").getLast().total() != 0)
                    throw new AssertionError("Deleted bucket activity reappeared");
            }
            System.out.println("Bucket activity tests passed");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(directory);
        }
    }
}
