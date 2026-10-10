package cloud.lunarsky.store;

import java.util.Map;

final class StorageLimits {
    private StorageLimits() {}

    static ObjectStorage.Limits fromEnvironment(Map<String, String> environment) {
        try {
            long maxObject = Long.parseLong(environment.getOrDefault("MAX_OBJECT_BYTES", "134217728"));
            long maxTotal = Long.parseLong(environment.getOrDefault("MAX_TOTAL_BYTES", "2147483648"));
            if (maxObject < 1 || maxObject > 1073741824L || maxTotal < maxObject)
                throw new NumberFormatException();
            return new ObjectStorage.Limits(maxObject, maxTotal);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid storage size limits", error);
        }
    }
}
