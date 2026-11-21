/*
 * Lab:       The experiment behind a virtual-thread decision: a platform pool, unbounded virtual threads,
 *            and virtual threads with a bulkhead and deadlines, at normal load and at 1.5x capacity
 * Chapters:  39 (the decision record, worked through), 12 §9 and §12, 27 §8
 * Run:       java VtDecision.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64 (2 vCPUs), 2026-11-20
 * Sources:   JEP 444 (virtual threads): https://openjdk.org/jeps/444
 * Output:    real threads and real time, so numbers vary by machine; the pattern doesn't. Runs about 40 seconds.
 *
 * The "database" is a pool of 20 connections and every query takes 30 ms, so it can answer about 666 requests
 * per second. Requests arrive open-loop at a fixed rate, and each caller gives up after 500 ms.
 */
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

public class VtDecision {

    static final int CONNECTIONS = 20, QUERY_MS = 30;
    static final long CLIENT_TIMEOUT_NS = 500_000_000L;
    enum Outcome { OK, FAST_FAIL, POOL_TIMEOUT, SKIPPED }

    enum Design {
        PLATFORM("platform pool: 200 threads, queue of 1,000"),
        VIRTUAL("virtual threads, no limits"),
        VIRTUAL_LIMITED("virtual threads + bulkhead of 20 + deadlines");
        final String label;
        Design(String label) { this.label = label; }
    }

    /** One measured run: n requests at a fixed rate through one design. */
    static final class Run {
        final Design design; final int n;
        final long[] start, end; final Outcome[] outcome; final boolean[] wasted;
        final Semaphore connections = new Semaphore(CONNECTIONS);
        final Semaphore bulkhead = new Semaphore(CONNECTIONS);
        final AtomicInteger inFlight = new AtomicInteger(), peak = new AtomicInteger();
        final CountDownLatch done;

        Run(Design design, int n) {
            this.design = design; this.n = n;
            start = new long[n]; end = new long[n]; outcome = new Outcome[n]; wasted = new boolean[n];
            done = new CountDownLatch(n);
        }

        void handle(int i) {
            try {
                if (design == Design.VIRTUAL_LIMITED) {
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
                if (design == Design.VIRTUAL_LIMITED && System.nanoTime() - start[i] > CLIENT_TIMEOUT_NS) { finish(i, Outcome.SKIPPED); return; }
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
            ThreadPoolExecutor pool = design == Design.PLATFORM
                    ? new ThreadPoolExecutor(200, 200, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1_000)) : null;
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
            if (pool != null) pool.shutdown();
        }
    }

    record Summary(double answeredPerSecond, double p50, double p99, int fastFails, int slowFails, int late, int wasted, int peak, double seconds) {
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
        return new Summary(answered.size() / seconds, p50, p99, fast, slow, late, wasted, r.peak.get(), seconds);
    }

    public static void main(String[] args) throws Exception {
        System.out.printf("Database: %d connections x %d ms per query (about %d answers/s). Callers give up after 500 ms.%n",
                          CONNECTIONS, QUERY_MS, CONNECTIONS * 1000 / QUERY_MS);
        System.out.println("Acceptance criteria at 1.5x capacity: p99 of answered requests <= 200 ms, under 1% of callers time out,");
        System.out.println("under 1% of queries run for callers who have gone, and every failure comes back within 100 ms.");
        int seconds = 4;
        for (int rate : new int[] {400, 1_000}) {
            System.out.println();
            System.out.printf("%s %,d requests/s for %d s%n", rate < 666 ? "Normal load:" : "Overload (1.5x capacity):", rate, seconds);
            System.out.printf("   %-46s %9s %15s %8s %8s %8s %7s %6s %s%n", "design", "answered", "p50 / p99 (ms)",
                              "fast", "slow", "callers", "wasted", "peak", "meets");
            System.out.printf("   %-46s %9s %15s %8s %8s %8s %7s %6s %s%n", "", "in time/s", "of answered", "failures",
                              "failures", "gave up", "queries", "in flight", "criteria");
            for (Design d : Design.values()) {
                new Run(d, 200).drive(200);                                       // warm-up, not measured
                Run run = new Run(d, rate * seconds);
                run.drive(rate);
                Summary s = summarise(run, seconds);
                System.out.println(String.format("   %-46s %9.0f %7.0f / %5.0f %,8d %,8d %,8d %,7d %,6d %s", d.label, s.answeredPerSecond(), s.p50(),
                                  s.p99(), s.fastFails(), s.slowFails(), s.late(), s.wasted(), s.peak(),
                                  rate < 666 ? "" : s.meetsCriteria(run.n) ? "yes" : "no").stripTrailing());
            }
        }
    }
}
