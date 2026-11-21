/*
 * Lab:       The 15 coding-interview pattern templates, each with tests
 * Chapters:  26 (coding and LLD)
 * Run:       java Patterns.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   none: classic problems
 * Output:    deterministic. Self-checking: prints ALL PASS, or exits with status 1 if a test fails.
 */
import java.util.*;

public class Patterns {
    // 1. two pointers
    static int[] pairWithSum(int[] sorted, int target) {
        int i = 0, j = sorted.length - 1;
        while (i < j) {
            int sum = sorted[i] + sorted[j];
            if (sum == target) return new int[]{i, j};
            if (sum < target) i++; else j--;
        }
        return null;
    }
    // 2. sliding window
    static int longestWithAtMostKDistinct(String s, int k) {
        int[] count = new int[128];
        int distinct = 0, best = 0;
        for (int left = 0, right = 0; right < s.length(); right++) {
            if (count[s.charAt(right)]++ == 0) distinct++;
            while (distinct > k) {
                if (--count[s.charAt(left++)] == 0) distinct--;
            }
            best = Math.max(best, right - left + 1);
        }
        return best;
    }
    // 3. prefix sums
    static int subarraysWithSum(int[] nums, int k) {
        Map<Integer, Integer> seen = new HashMap<>(Map.of(0, 1));
        int prefix = 0, count = 0;
        for (int x : nums) {
            prefix += x;
            count += seen.getOrDefault(prefix - k, 0);
            seen.merge(prefix, 1, Integer::sum);
        }
        return count;
    }
    // 4. binary search
    static int lowerBound(int[] a, int target) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < target) lo = mid + 1; else hi = mid;
        }
        return lo;
    }
    static int minCapacity(int[] weights, int days) {
        int lo = Arrays.stream(weights).max().orElseThrow(), hi = Arrays.stream(weights).sum();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (daysNeeded(weights, mid) <= days) hi = mid; else lo = mid + 1;
        }
        return lo;
    }
    static int daysNeeded(int[] weights, int capacity) {
        int days = 1, load = 0;
        for (int w : weights) {
            if (load + w > capacity) { days++; load = 0; }
            load += w;
        }
        return days;
    }
    // 5. monotonic stack
    static int[] nextGreater(int[] a) {
        int[] result = new int[a.length];
        Arrays.fill(result, -1);
        Deque<Integer> stack = new ArrayDeque<>();
        for (int i = 0; i < a.length; i++) {
            while (!stack.isEmpty() && a[stack.peek()] < a[i]) result[stack.pop()] = a[i];
            stack.push(i);
        }
        return result;
    }
    // 6. monotonic deque
    static int[] windowMax(int[] a, int k) {
        int[] out = new int[a.length - k + 1];
        Deque<Integer> dq = new ArrayDeque<>();
        for (int i = 0; i < a.length; i++) {
            if (!dq.isEmpty() && dq.peekFirst() <= i - k) dq.pollFirst();
            while (!dq.isEmpty() && a[dq.peekLast()] <= a[i]) dq.pollLast();
            dq.offerLast(i);
            if (i >= k - 1) out[i - k + 1] = a[dq.peekFirst()];
        }
        return out;
    }
    // 7. top-k
    static List<String> topKFrequent(List<String> words, int k) {
        Map<String, Integer> freq = new HashMap<>();
        for (String w : words) freq.merge(w, 1, Integer::sum);
        Comparator<Map.Entry<String, Integer>> evictFirst =
                Map.Entry.<String, Integer>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()));
        PriorityQueue<Map.Entry<String, Integer>> heap = new PriorityQueue<>(evictFirst);
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            heap.offer(e);
            if (heap.size() > k) heap.poll();
        }
        List<String> result = new ArrayList<>(k);
        while (!heap.isEmpty()) result.add(heap.poll().getKey());
        return result.reversed();
    }
    // 8. intervals
    static int[][] merge(int[][] intervals) {
        int[][] sorted = intervals.clone();
        Arrays.sort(sorted, Comparator.comparingInt(iv -> iv[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] iv : sorted) {
            if (merged.isEmpty() || merged.getLast()[1] < iv[0]) merged.add(iv.clone());
            else merged.getLast()[1] = Math.max(merged.getLast()[1], iv[1]);
        }
        return merged.toArray(int[][]::new);
    }
    static int minMeetingRooms(int[][] meetings) {
        int[][] sorted = meetings.clone();
        Arrays.sort(sorted, Comparator.comparingInt(m -> m[0]));
        PriorityQueue<Integer> endTimes = new PriorityQueue<>();
        for (int[] m : sorted) {
            if (!endTimes.isEmpty() && endTimes.peek() <= m[0]) endTimes.poll();
            endTimes.offer(m[1]);
        }
        return endTimes.size();
    }
    // 9. BFS
    static int shortestPath(char[][] grid, int sr, int sc, int tr, int tc) {
        int rows = grid.length, cols = grid[0].length;
        int[][] dist = new int[rows][cols];
        for (int[] row : dist) Arrays.fill(row, -1);
        Deque<int[]> queue = new ArrayDeque<>();
        queue.offer(new int[]{sr, sc});
        dist[sr][sc] = 0;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!queue.isEmpty()) {
            int[] cur = queue.poll();
            if (cur[0] == tr && cur[1] == tc) return dist[tr][tc];
            for (int[] d : dirs) {
                int r = cur[0] + d[0], c = cur[1] + d[1];
                if (r >= 0 && r < rows && c >= 0 && c < cols && grid[r][c] != '#' && dist[r][c] == -1) {
                    dist[r][c] = dist[cur[0]][cur[1]] + 1;
                    queue.offer(new int[]{r, c});
                }
            }
        }
        return -1;
    }
    // 10. backtracking
    static List<List<Integer>> combinationSum(int[] candidates, int target) {
        int[] sorted = candidates.clone();
        Arrays.sort(sorted);
        List<List<Integer>> out = new ArrayList<>();
        backtrack(sorted, target, 0, new ArrayList<>(), out);
        return out;
    }
    private static void backtrack(int[] c, int remaining, int start, List<Integer> path, List<List<Integer>> out) {
        if (remaining == 0) { out.add(List.copyOf(path)); return; }
        for (int i = start; i < c.length && c[i] <= remaining; i++) {
            path.addLast(c[i]);
            backtrack(c, remaining - c[i], i, path, out);
            path.removeLast();
        }
    }
    // 11. topo sort
    static List<Integer> topoOrder(int n, int[][] edges) {
        List<List<Integer>> adj = new ArrayList<>(n);
        for (int i = 0; i < n; i++) adj.add(new ArrayList<>());
        int[] indegree = new int[n];
        for (int[] e : edges) { adj.get(e[0]).add(e[1]); indegree[e[1]]++; }
        Deque<Integer> ready = new ArrayDeque<>();
        for (int i = 0; i < n; i++) if (indegree[i] == 0) ready.offer(i);
        List<Integer> order = new ArrayList<>(n);
        while (!ready.isEmpty()) {
            int node = ready.poll();
            order.add(node);
            for (int next : adj.get(node)) if (--indegree[next] == 0) ready.offer(next);
        }
        return order.size() == n ? order : List.of();
    }
    // 12. union-find
    static final class DisjointSet {
        private final int[] parent, rank;
        private int components;
        DisjointSet(int n) {
            parent = new int[n]; rank = new int[n]; components = n;
            for (int i = 0; i < n; i++) parent[i] = i;
        }
        int find(int x) {
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
            return x;
        }
        boolean union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra == rb) return false;
            if (rank[ra] < rank[rb]) { int t = ra; ra = rb; rb = t; }
            parent[rb] = ra;
            if (rank[ra] == rank[rb]) rank[ra]++;
            components--;
            return true;
        }
        int components() { return components; }
    }
    // 13. dijkstra
    record Edge(int to, int weight) {}
    static long[] dijkstra(List<List<Edge>> graph, int source) {
        long[] dist = new long[graph.size()];
        Arrays.fill(dist, Long.MAX_VALUE);
        dist[source] = 0;
        PriorityQueue<long[]> pq = new PriorityQueue<>(Comparator.comparingLong(entry -> entry[1]));
        pq.offer(new long[]{source, 0});
        while (!pq.isEmpty()) {
            long[] top = pq.poll();
            int node = (int) top[0];
            if (top[1] > dist[node]) continue;
            for (Edge e : graph.get(node)) {
                long candidate = dist[node] + e.weight();
                if (candidate < dist[e.to()]) {
                    dist[e.to()] = candidate;
                    pq.offer(new long[]{e.to(), candidate});
                }
            }
        }
        return dist;
    }
    // 14. DP
    static int knapsack(int[] weight, int[] value, int capacity) {
        int[] best = new int[capacity + 1];
        for (int i = 0; i < weight.length; i++)
            for (int c = capacity; c >= weight[i]; c--)
                best[c] = Math.max(best[c], best[c - weight[i]] + value[i]);
        return best[capacity];
    }
    static int lengthOfLIS(int[] nums) {
        int[] tails = new int[nums.length];
        int size = 0;
        for (int x : nums) {
            int i = Arrays.binarySearch(tails, 0, size, x);
            if (i < 0) i = -(i + 1);
            tails[i] = x;
            if (i == size) size++;
        }
        return size;
    }
    static int editDistance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int replace = prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                cur[j] = Math.min(replace, 1 + Math.min(prev[j], cur[j - 1]));
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
    // 15. trie
    static final class Trie {
        private static final class Node { final Node[] next = new Node[26]; boolean word; }
        private final Node root = new Node();
        void insert(String w) {
            Node n = root;
            for (int i = 0; i < w.length(); i++) {
                int c = w.charAt(i) - 'a';
                if (n.next[c] == null) n.next[c] = new Node();
                n = n.next[c];
            }
            n.word = true;
        }
        boolean contains(String w) { Node n = walk(w); return n != null && n.word; }
        boolean startsWith(String prefix) { return walk(prefix) != null; }
        private Node walk(String s) {
            Node n = root;
            for (int i = 0; i < s.length() && n != null; i++) n = n.next[s.charAt(i) - 'a'];
            return n;
        }
    }
    // fast/slow
    static final class ListNode { int val; ListNode next; ListNode(int v) { val = v; } }
    static ListNode cycleStart(ListNode head) {
        ListNode slow = head, fast = head;
        while (fast != null && fast.next != null) {
            slow = slow.next; fast = fast.next.next;
            if (slow == fast) {
                ListNode p = head;
                while (p != slow) { p = p.next; slow = slow.next; }
                return p;
            }
        }
        return null;
    }

    static int fails = 0;
    static void check(String name, Object actual, Object expected) {
        boolean ok = Objects.deepEquals(actual, expected);
        if (!ok) fails++;
        System.out.printf("%-4s %-28s %s%n", ok ? "ok" : "FAIL", name, ok ? "" : ("got " + deep(actual) + " expected " + deep(expected)));
    }
    static String deep(Object o) { return o instanceof Object[] a ? Arrays.deepToString(a) : o instanceof int[] a ? Arrays.toString(a) : o instanceof long[] a ? Arrays.toString(a) : String.valueOf(o); }

    public static void main(String[] args) {
        check("pairWithSum", pairWithSum(new int[]{1, 2, 4, 7, 11, 15}, 15), new int[]{2, 4});
        check("atMostKDistinct", longestWithAtMostKDistinct("eceba", 2), 3);
        check("atMostKDistinct2", longestWithAtMostKDistinct("aaabbccd", 2), 5);
        check("subarraysWithSum", subarraysWithSum(new int[]{1, 2, 3, -3, 3}, 3), 5);
        check("lowerBound", lowerBound(new int[]{1, 3, 3, 3, 8}, 3), 1);
        check("lowerBound-missing", lowerBound(new int[]{1, 3, 3, 3, 8}, 9), 5);
        check("minCapacity", minCapacity(new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 5), 15);
        check("nextGreater", nextGreater(new int[]{2, 1, 2, 4, 3}), new int[]{4, 2, 4, -1, -1});
        check("windowMax", windowMax(new int[]{1, 3, -1, -3, 5, 3, 6, 7}, 3), new int[]{3, 3, 5, 5, 6, 7});
        check("topK-1", topKFrequent(List.of("i", "love", "leetcode", "i", "love", "coding"), 2), List.of("i", "love"));
        check("topK-2", topKFrequent(List.of("the", "day", "is", "sunny", "the", "the", "the", "sunny", "is", "is"), 4), List.of("the", "is", "sunny", "day"));
        check("merge", merge(new int[][]{{8, 10}, {1, 3}, {2, 6}, {15, 18}}), new int[][]{{1, 6}, {8, 10}, {15, 18}});
        check("meetingRooms", minMeetingRooms(new int[][]{{0, 30}, {5, 10}, {15, 20}}), 2);
        char[][] grid = { "..#.".toCharArray(), ".##.".toCharArray(), "....".toCharArray() };
        check("bfs", shortestPath(grid, 0, 0, 0, 3), 7);
        check("combinationSum", combinationSum(new int[]{2, 3, 6, 7}, 7), List.of(List.of(2, 2, 3), List.of(7)));
        check("topo", topoOrder(4, new int[][]{{0, 1}, {0, 2}, {1, 3}, {2, 3}}), List.of(0, 1, 2, 3));
        check("topo-cycle", topoOrder(3, new int[][]{{0, 1}, {1, 2}, {2, 0}}), List.of());
        DisjointSet ds = new DisjointSet(5);
        ds.union(0, 1); ds.union(3, 4);
        check("unionFind-dup", ds.union(1, 0), false);
        check("unionFind-components", ds.components(), 3);
        List<List<Edge>> g = List.of(List.of(new Edge(1, 4), new Edge(2, 1)), List.of(new Edge(3, 1)), List.of(new Edge(1, 2), new Edge(3, 5)), List.of());
        check("dijkstra", dijkstra(g, 0), new long[]{0, 3, 1, 4});
        check("knapsack", knapsack(new int[]{1, 3, 4, 5}, new int[]{1, 4, 5, 7}, 7), 9);
        check("LIS", lengthOfLIS(new int[]{10, 9, 2, 5, 3, 7, 101, 18}), 4);
        check("editDistance", editDistance("horse", "ros"), 3);
        Trie t = new Trie(); t.insert("apple"); t.insert("app");
        check("trie", List.of(t.contains("app"), t.contains("appl"), t.startsWith("appl"), t.startsWith("b")), List.of(true, false, true, false));
        ListNode a = new ListNode(1), b = new ListNode(2), c = new ListNode(3), d = new ListNode(4);
        a.next = b; b.next = c; c.next = d; d.next = b;
        check("cycleStart", cycleStart(a) == b, true);
        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        if (fails > 0) System.exit(1);
    }
}
