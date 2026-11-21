/*
 * Lab:       The experiment behind a virtual-thread decision: platform and virtual threads, each with and without a
 *            bulkhead and deadlines, at normal load and 1.5x capacity; then a workload where the thread model matters
 * Chapters:  39 (the decision record, worked through), 12 §9 and §12, 27 §8
 * Run:       java VtDecision.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64 (2 vCPUs), 2026-11-21
 * Sources:   JEP 444 (virtual threads): https://openjdk.org/jeps/444
 * Output:    real threads and real time, so numbers vary by machine; the pattern doesn't. Runs about a minute.
 *
 * Part 1: the "database" is a pool of 20 connections and every query takes 30 ms, so it can answer about 666
 * requests per second. Part 2: each request waits 300 ms on a slow downstream service, and nothing else limits
 * them. Requests arrive open-loop at a fixed rate, and each caller gives up after 500 ms.
 */
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class VtDecision {

    static final int CONNECTIONS = 20, QUERY_MS = 30, DOWNSTREAM_MS = 300;
    static final long CLIENT_TIMEOUT_NS = 500_000_000L;
    static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();   // counts platform threads only
    enum Outcome { OK, FAST_FAIL, POOL_TIMEOUT, SKIPPED }
    enum Workload { DATABASE, DOWNSTREAM }

    enum Design {
        PLATFORM("platform pool: 200 threads, queue of 1,000", 200, false),
        VIRTUAL("virtual threads, no limits", 0, false),
        PLATFORM_LIMITED("platform pool + bulkhead of 20 + deadlines", 200, true),
        VIRTUAL_LIMITED("virtual threads + bulkhead of 20 + deadlines", 0, true),
        PLATFORM_1000("platform pool: 1,000 threads, queue of 1,000", 1_000, false);
        final String label;
        final int poolThreads;                                                    // 0: a virtual thread per request
        final boolean limits;
        Design(String label, int poolThreads, boolean limits) { this.label = label; this.poolThreads = poolThreads; this.limits = limits; }
    }

    /** One measured run: n requests at a fixed rate through one design. */
    static final class Run {
        final Design design; final Workload workload; final int n;
        final long[] start, end; final Outcome[] outcome; final boolean[] wasted;
        final Semaphore connections = new Semaphore(CONNECTIONS);
        final Semaphore bulkhead = new Semaphore(CONNECTIONS);
        final AtomicInteger inFlight = new AtomicInteger(), peak = new AtomicInteger();
        final CountDownLatch done;

        Run(Design design, Workload workload, int n) {
            this.design = design; this.workload = workload; this.n = n;
            start = new long[n]; end = new long[n]; outcome = new Outcome[n]; wasted = new boolean[n];
            done = new CountDownLatch(n);
        }

        void handle(int i) {
            try {
                if (workload == Workload.DOWNSTREAM) {                            // a slow call that nothing else limits
                    wasted[i] = System.nanoTime() - start[i] > CLIENT_TIMEOUT_NS;
                    Thread.sleep(DOWNSTREAM_MS);
                    finish(i, Outcome.OK);
                } else if (design.limits) {
                    long left = start[i] + CLIENT_TIMEOUT_NS - System.nanoTime();
                    if (left <= 0) { finish(i, Outcome.SKIPPED); return; }            // the caller has gone: don't start
                    if (!bulkhead.tryAcquire(Math.min(left, 50_000_000L), TimeUnit.NANOSECONDS)) { finish(i, Outcome.FAST_FAIL); return; }
                    try { query(i, 1_000); } finally { bulkhead.release(); }
                } else {
                    query(i, 1_000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void query(int i, long acquireTimeoutMs) throws InterruptedException {
            if (!connections.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)) { finish(i, Outcome.POOL_TIMEOUT); return; }
            try {
                if (design.limits && System.nanoTime() - start[i] > CLIENT_TIMEOUT_NS) { finish(i, Outcome.SKIPPED); return; }
                wasted[i] = System.nanoTime() - start[i] > CLIENT_TIMEOUT_NS;          // nobody is waiting for this answer
                Thread.sleep(QUERY_MS);
            } finally {
                connections.release();
            }
            finish(i, Outcome.OK);
        }

        void finish(int i, Outcome o) {
            end[i] = System.nanoTime();
            outcome[i] = o;
            inFlight.decrementAndGet();
            done.countDown();
        }

        /** Open-loop load: request i is due at t0 + i / rate, whether or not earlier ones have finished. */
        void drive(int perSecond) throws InterruptedException {
            ThreadPoolExecutor pool = design.poolThreads > 0 ? new ThreadPoolExecutor(design.poolThreads, design.poolThreads, 0,
                    TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1_000)) : null;
            long t0 = System.nanoTime() + 20_000_000L, gap = 1_000_000_000L / perSecond;
            for (int i = 0; i < n; i++) {
                long due = t0 + i * gap;
                for (long wait; (wait = due - System.nanoTime()) > 0; ) LockSupport.parkNanos(wait);
                start[i] = due;
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                final int id = i;
                if (pool == null) {
                    Thread.ofVirtual().start(() -> handle(id));
                } else {
                    try { pool.execute(() -> handle(id)); } catch (RejectedExecutionException e) { finish(id, Outcome.FAST_FAIL); }
                }
            }
            done.await();
            peakPlatformThreads = THREADS.getPeakThreadCount();
            if (pool != null) {
                pool.shutdown();
                pool.awaitTermination(10, TimeUnit.SECONDS);
            }
        }

        int peakPlatformThreads;
    }

    record Summary(double answeredPerSecond, double p50, double p99, int fastFails, int slowFails, int late, int wasted, int peak,
                   int platformThreads, double seconds) {
        boolean meetsCriteria(int n) {
            return p99 <= 200 && late <= n / 100 && wasted <= n / 100 && slowFails == 0;
        }
    }

    static Summary summarise(Run r, double seconds) {
        List<Double> answered = new ArrayList<>();
        int fast = 0, slow = 0, late = 0, wasted = 0;
        for (int i = 0; i < r.n; i++) {
            long latency = r.end[i] - r.start[i];
            if (latency > CLIENT_TIMEOUT_NS) late++;
            else if (r.outcome[i] == Outcome.OK) answered.add(latency / 1e6);
            if (r.outcome[i] != Outcome.OK) { if (latency <= 100_000_000L) fast++; else slow++; }
            if (r.wasted[i]) wasted++;
        }
        Collections.sort(answered);
        double p50 = answered.isEmpty() ? Double.NaN : answered.get(answered.size() / 2);
        double p99 = answered.isEmpty() ? Double.NaN : answered.get((int) Math.ceil(0.99 * answered.size()) - 1);
        return new Summary(answered.size() / seconds, p50, p99, fast, slow, late, wasted, r.peak.get(), r.peakPlatformThreads, seconds);
    }

    public static void main(String[] args) throws Exception {
        System.out.printf("Part 1. Database: %d connections x %d ms per query (about %d answers/s). Callers give up after 500 ms.%n",
                          CONNECTIONS, QUERY_MS, CONNECTIONS * 1000 / QUERY_MS);
        System.out.println("Acceptance criteria at 1.5x capacity: p99 of answered requests <= 200 ms, under 1% of callers time out,");
        System.out.println("under 1% of queries run for callers who have gone, and every failure comes back within 100 ms.");
        for (int rate : new int[] {400, 1_000})
            table(rate < 666 ? "Normal load:" : "Overload (1.5x capacity):", Workload.DATABASE, rate,
                  List.of(Design.PLATFORM, Design.VIRTUAL, Design.PLATFORM_LIMITED, Design.VIRTUAL_LIMITED), rate >= 666);
        System.out.println();
        System.out.printf("Part 2. Where the thread model matters: each request waits %d ms on a slow downstream service, and nothing%n", DOWNSTREAM_MS);
        System.out.println("else limits them, so at 1,000 requests/s about 300 are waiting at any moment.");
        table("Load:", Workload.DOWNSTREAM, 1_000, List.of(Design.PLATFORM, Design.PLATFORM_1000, Design.VIRTUAL), false);
    }

    static void table(String title, Workload workload, int rate, List<Design> designs, boolean judge) throws InterruptedException {
        int seconds = 4;
        System.out.println();
        System.out.printf("%s %,d requests/s for %d s%n", title, rate, seconds);
        System.out.println(String.format("   %-46s %9s %15s %8s %8s %8s %7s %9s %9s %s", "design", "answered", "p50 / p99 (ms)",
                          "fast", "slow", "callers", "wasted", "peak", "platform", judge ? "meets" : "").stripTrailing());
        System.out.println(String.format("   %-46s %9s %15s %8s %8s %8s %7s %9s %9s %s", "", "in time/s", "of answered", "failures",
                          "failures", "gave up", workload == Workload.DATABASE ? "queries" : "calls", "in flight", "threads",
                          judge ? "criteria" : "").stripTrailing());
        for (Design d : designs) {
            new Run(d, workload, 200).drive(200);                                 // warm-up, not measured
            THREADS.resetPeakThreadCount();
            Run run = new Run(d, workload, rate * seconds);
            run.drive(rate);
            Summary s = summarise(run, seconds);
            System.out.println(String.format("   %-46s %,9.0f %7.0f / %5.0f %,8d %,8d %,8d %,7d %,9d %,9d %s", d.label, s.answeredPerSecond(),
                              s.p50(), s.p99(), s.fastFails(), s.slowFails(), s.late(), s.wasted(), s.peak(), s.platformThreads(),
                              judge ? (s.meetsCriteria(run.n) ? "yes" : "no") : "").stripTrailing());
        }
    }
}
