package cloud.lunarsky.store;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class PlacementPolicy {
    private PlacementPolicy() {}

    static List<Integer> candidates(UUID segmentId, NodeClient nodes, boolean testNodeDomains) {
        Map<UUID, List<Integer>> byHost = new HashMap<>();
        for (int i = 0; i < nodes.count(); i++)
            byHost.computeIfAbsent(nodes.faultDomain(i, testNodeDomains), ignored -> new ArrayList<>()).add(i);
        List<UUID> hosts = new ArrayList<>(byHost.keySet());
        hosts.sort(Comparator.comparingLong((UUID host) -> score(segmentId, host)).reversed());
        int longest = 0;
        for (List<Integer> group : byHost.values()) {
            group.sort(Comparator.comparingLong((Integer index) -> score(segmentId, nodes.node(index).id())).reversed());
            longest = Math.max(longest, group.size());
        }
        List<Integer> order = new ArrayList<>(nodes.count());
        for (int round = 0; round < longest; round++)
            for (UUID host : hosts)
                if (round < byHost.get(host).size()) order.add(byHost.get(host).get(round));
        return order;
    }

    private static long score(UUID segment, UUID candidate) {
        ByteBuffer bytes = ByteBuffer.allocate(32);
        bytes.putLong(segment.getMostSignificantBits()).putLong(segment.getLeastSignificantBits());
        bytes.putLong(candidate.getMostSignificantBits()).putLong(candidate.getLeastSignificantBits());
        return ByteBuffer.wrap(SigV4.hash(bytes.array())).getLong();
    }
}
