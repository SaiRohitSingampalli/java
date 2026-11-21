/*
 * Lab:       A one-shot latch built on AbstractQueuedSynchronizer, and a fork/join sum
 * Chapters:  12 (advanced concurrency and Loom)
 * Run:       java OneShotLatchAndForkJoin.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   AbstractQueuedSynchronizer and ForkJoinPool API docs; Doug Lea, The java.util.concurrent Synchronizer Framework (2004)
 * Output:    deterministic.
 */
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class OneShotLatchAndForkJoin {
    public static void main(String[] args) throws Exception {
        OneShotLatch latch = new OneShotLatch();
        AtomicInteger passed = new AtomicInteger();
        Thread[] waiters = new Thread[8];
        for (int i = 0; i < waiters.length; i++) {
            waiters[i] = new Thread(() -> { try { latch.await(); passed.incrementAndGet(); } catch (InterruptedException e) { } });
            waiters[i].start();
        }
        Thread.sleep(200);
        System.out.println("before signal: " + passed.get() + " of 8 threads passed");
        latch.signal();
        for (Thread t : waiters) t.join(5000);
        System.out.println("after signal:  " + passed.get() + " of 8 threads passed");

        long[] data = new long[5_000_000];
        for (int i = 0; i < data.length; i++) data[i] = i + 1;
        long total = ForkJoinPool.commonPool().invoke(new SumTask(data, 0, data.length));
        System.out.println("fork/join sum = " + total + " (expected " + (5_000_000L * 5_000_001L / 2) + ")");
    }
}

final class OneShotLatch {
    private final Sync sync = new Sync();
    public void signal() { sync.releaseShared(0); }
    public void await() throws InterruptedException { sync.acquireSharedInterruptibly(0); }

    private static final class Sync extends AbstractQueuedSynchronizer {
        @Override protected int tryAcquireShared(int ignored) { return getState() == 1 ? 1 : -1; }  // open?
        @Override protected boolean tryReleaseShared(int ignored) { setState(1); return true; }     // open it
    }
}

final class SumTask extends RecursiveTask<Long> {
    private static final int THRESHOLD = 10_000;
    private final long[] data; private final int lo, hi;
    SumTask(long[] data, int lo, int hi) { this.data = data; this.lo = lo; this.hi = hi; }

    @Override protected Long compute() {
        if (hi - lo <= THRESHOLD) {                         // small enough: do it directly
            long sum = 0;
            for (int i = lo; i < hi; i++) sum += data[i];
            return sum;
        }
        int mid = (lo + hi) >>> 1;
        SumTask left = new SumTask(data, lo, mid);
        left.fork();                                        // push onto my deque (others may steal it)
        long right = new SumTask(data, mid, hi).compute();  // work on the other half myself
        return right + left.join();                         // join: run it myself if nobody stole it
    }
}
