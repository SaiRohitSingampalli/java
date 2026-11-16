import java.util.*;

public class Traps {
    static Map<Integer, Long> memo = new HashMap<>();
    static long fib(int n) {
        if (n < 2) return n;
        return memo.computeIfAbsent(n, k -> fib(k - 1) + fib(k - 2));
    }
    static int depth = 0;
    static void recurse() { depth++; recurse(); }

    public static void main(String[] args) throws Exception {
        Integer[] xs = {3, Integer.MIN_VALUE, -5, Integer.MAX_VALUE, 0};
        Integer[] bad = xs.clone();
        Arrays.sort(bad, (a, b) -> a - b);
        Integer[] good = xs.clone();
        Arrays.sort(good, Integer::compare);
        System.out.println("(a, b) -> a - b   : " + Arrays.toString(bad));
        System.out.println("Integer::compare  : " + Arrays.toString(good));

        Map<String, Integer> counts = new HashMap<>(Map.of("a", 127, "b", 127, "c", 1000, "d", 1000));
        System.out.println("127 == 127   : " + (counts.get("a") == counts.get("b")));
        System.out.println("1000 == 1000 : " + (counts.get("c") == counts.get("d")));

        System.out.println("Math.abs(Integer.MIN_VALUE) = " + Math.abs(Integer.MIN_VALUE));
        System.out.println("-7 % 3 = " + (-7 % 3) + ",  Math.floorMod(-7, 3) = " + Math.floorMod(-7, 3));

        List<Integer> list = new ArrayList<>(List.of(10, 20, 30));
        list.remove(1);
        System.out.println("remove(1) on [10, 20, 30] -> " + list);
        list.remove(Integer.valueOf(10));
        System.out.println("remove(Integer.valueOf(10)) -> " + list);

        System.out.println("Arrays.asList(new int[]{1, 2, 3}).size() = " + Arrays.asList(new int[]{1, 2, 3}).size());

        int lo = 1_500_000_000, hi = 2_000_000_000;
        System.out.println("(lo + hi) / 2 = " + ((lo + hi) / 2) + ",  (lo + hi) >>> 1 = " + ((lo + hi) >>> 1));

        Set<int[]> arrays = new HashSet<>();
        arrays.add(new int[]{1, 2});
        System.out.println("HashSet<int[]> contains {1, 2}? " + arrays.contains(new int[]{1, 2}));

        try {
            System.out.println(fib(30));
        } catch (ConcurrentModificationException e) {
            System.out.println("recursive computeIfAbsent -> " + e);
        }

        Thread t = new Thread(() -> {
            try { recurse(); } catch (StackOverflowError e) { System.out.println("default stack: StackOverflowError at depth ~" + depth); }
        });
        t.start(); t.join();
        depth = 0;
        Thread big = new Thread(null, () -> {
            try { recurse(); } catch (StackOverflowError e) { System.out.println("256 MB stack:  StackOverflowError at depth ~" + depth); }
        }, "big-stack", 256L * 1024 * 1024);
        big.start(); big.join();
    }
}
