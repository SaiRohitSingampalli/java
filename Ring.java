import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;

public class Ring {
    static final class ConsistentHashRing<N> {
        private final NavigableMap<Long, N> ring = new TreeMap<>();
        private final int virtualNodes;
        private final Function<String, Long> hash;
        ConsistentHashRing(int virtualNodes, Function<String, Long> hash) { this.virtualNodes = virtualNodes; this.hash = hash; }
        void add(N node)    { for (int i = 0; i < virtualNodes; i++) ring.put(hash.apply(node + "#" + i), node); }
        void remove(N node) { for (int i = 0; i < virtualNodes; i++) ring.remove(hash.apply(node + "#" + i)); }
        N nodeFor(String key) {
            if (ring.isEmpty()) throw new IllegalStateException("empty ring");
            Map.Entry<Long, N> e = ring.ceilingEntry(hash.apply(key));
            return (e != null ? e : ring.firstEntry()).getValue();
        }
    }
    static long sha256Long(String s) {
        try { return ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))).getLong(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static void main(String[] args) {
        int keys = 100_000;
        for (int vnodes : new int[]{1, 10, 100, 200}) {
            var ring = new ConsistentHashRing<String>(vnodes, Ring::sha256Long);
            for (String n : List.of("node-A", "node-B", "node-C")) ring.add(n);
            Map<String, Integer> counts = new TreeMap<>();
            Map<String, String> before = new HashMap<>();
            for (int k = 0; k < keys; k++) { String key = "user:" + k; String n = ring.nodeFor(key); before.put(key, n); counts.merge(n, 1, Integer::sum); }
            ring.add("node-D");
            int moved = 0;
            for (int k = 0; k < keys; k++) { String key = "user:" + k; if (!ring.nodeFor(key).equals(before.get(key))) moved++; }
            System.out.printf("vnodes=%3d  3-node split=%s  keys moved after adding node-D=%.1f%%%n", vnodes, counts, 100.0 * moved / keys);
        }
        // naive modulo for comparison
        int moved = 0;
        for (int k = 0; k < keys; k++) { long h = sha256Long("user:" + k); if (Math.floorMod(h, 3) != Math.floorMod(h, 4)) moved++; }
        System.out.printf("naive hash %% N: keys moved going from 3 to 4 nodes=%.1f%%%n", 100.0 * moved / keys);
    }
}
