/*
 * Lab:       ConcurrentHashMap rejects a recursive computeIfAbsent
 * Chapters:  26 (coding and LLD), 06
 * Run:       java RecursiveComputeIfAbsent.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   ConcurrentHashMap.computeIfAbsent API docs
 * Output:    deterministic: the IllegalStateException is caught and printed.
 */
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
