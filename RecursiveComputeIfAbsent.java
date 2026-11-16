import java.util.concurrent.*;
public class RecursiveComputeIfAbsent {
    static ConcurrentHashMap<Integer, Long> memo = new ConcurrentHashMap<>();
    static long fib(int n) { return n < 2 ? n : memo.computeIfAbsent(n, k -> fib(k - 1) + fib(k - 2)); }
    public static void main(String[] a) throws Exception {
        Thread t = new Thread(() -> {
            try { System.out.println("fib(30) = " + fib(30)); }
            catch (Throwable e) { System.out.println("ConcurrentHashMap recursive computeIfAbsent -> " + e); }
        });
        t.setDaemon(true); t.start(); t.join(5000);
        if (t.isAlive()) System.out.println("still running after 5 s (hung)");
    }
}
