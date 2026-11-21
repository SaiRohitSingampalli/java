/*
 * Lab:       Virtual-thread pinning inside synchronized, and how ReentrantLock and JDK 24 avoid it
 * Chapters:  32 (case 3), 12 §12
 * Run:       java Pinning.java synchronized    and    java Pinning.java ReentrantLock
 *            on JDK 21 and on JDK 25; on JDK 21, add -Djdk.tracePinnedThreads=short
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JEP 444 (virtual threads); JEP 491 (synchronize virtual threads without pinning, JDK 24)
 * Output:    timings vary; JDK 21 and JDK 25 differ by design.
 */
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
public class Pinning {
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }
    public static void main(String[] args) throws Exception {
        boolean useSync = args[0].equals("synchronized");
        int tasks = 200;
        long t0 = System.nanoTime();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks; i++) {
                Object monitor = new Object();
                ReentrantLock lock = new ReentrantLock();                    // one lock per task: zero contention
                exec.submit(() -> {
                    if (useSync) { synchronized (monitor) { sleep(50); } }   // e.g. a legacy client doing blocking I/O inside synchronized
                    else { lock.lock(); try { sleep(50); } finally { lock.unlock(); } }
                    return null;
                });
            }
        }
        System.out.printf("JDK %d, %-12s: %d virtual threads x 50 ms blocking -> %5d ms  (%d carrier threads)%n",
                Runtime.version().feature(), args[0], tasks, (System.nanoTime() - t0) / 1_000_000, Runtime.getRuntime().availableProcessors());
    }
}
