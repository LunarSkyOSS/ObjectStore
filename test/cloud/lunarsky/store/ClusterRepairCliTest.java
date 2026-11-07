package cloud.lunarsky.store;

public final class ClusterRepairCliTest {
    public static void main(String[] args) throws Exception {
        try {
            ClusterRepair.main(new String[] { "--invalid" });
            throw new AssertionError("Cluster repair accepted invalid arguments");
        } catch (IllegalArgumentException expected) {
            if (!expected.getMessage().equals("Usage: objectstore cluster-repair [--loop]"))
                throw new AssertionError("Cluster repair CLI entrypoint changed", expected);
        }
        System.out.println("Cluster repair CLI entrypoint passed");
    }
}
