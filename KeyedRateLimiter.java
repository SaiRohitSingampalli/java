/*
 * Lab:       A per-key token-bucket rate limiter with an injectable clock, and its tests
 * Chapters:  33 (Mock 4), 24 §6
 * Run:       java KeyedRateLimiter.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   the token-bucket algorithm (Chapter 24 §6)
 * Output:    deterministic (a frozen clock). Self-checking: exits with status 1 if an invariant fails, silent otherwise.
 */
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

public final class KeyedRateLimiter {
    private static final class Bucket {
        private final long capacity;
        private final double refillPerNano;
        private double tokens;
        private long lastRefill;
        private volatile long lastUsed;

        Bucket(long capacity, double refillPerSecond, long now) {
            this.capacity = capacity;
            this.refillPerNano = refillPerSecond / 1_000_000_000.0;
            this.tokens = capacity;
            this.lastRefill = now;
            this.lastUsed = now;
        }

        synchronized boolean tryAcquire(long now) {
            tokens = Math.min(capacity, tokens + (now - lastRefill) * refillPerNano);
            lastRefill = now;
            lastUsed = now;
            if (tokens >= 1.0) { tokens -= 1.0; return true; }
            return false;
        }
    }

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final long capacity;
    private final double refillPerSecond;
    private final LongSupplier nanoClock;

    public KeyedRateLimiter(long capacity, double refillPerSecond, LongSupplier nanoClock) {
        if (capacity < 1 || refillPerSecond <= 0) throw new IllegalArgumentException("capacity >= 1 and refill > 0 required");
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.nanoClock = nanoClock;
    }

    public boolean tryAcquire(String key) {
        long now = nanoClock.getAsLong();
        return buckets.computeIfAbsent(key, k -> new Bucket(capacity, refillPerSecond, now)).tryAcquire(now);
    }

    /** A bucket idle long enough to be full again behaves exactly like a new one, so it can be dropped. */
    public int evictIdle() {
        long now = nanoClock.getAsLong();
        long fullRefillNanos = (long) (capacity / refillPerSecond * 1_000_000_000L);
        int before = buckets.size();
        buckets.entrySet().removeIf(e -> now - e.getValue().lastUsed > fullRefillNanos);
        return before - buckets.size();
    }

    public int trackedKeys() { return buckets.size(); }

    public static void main(String[] args) throws Exception {
        AtomicLong clock = new AtomicLong();
        KeyedRateLimiter limiter = new KeyedRateLimiter(5, 1.0, clock::get);       // bursts of 5, then 1 per second
        List<Boolean> alice = new ArrayList<>();
        for (int i = 0; i < 6; i++) alice.add(limiter.tryAcquire("alice"));
        System.out.println("alice, 6 calls at t=0s : " + alice);
        check("a full bucket allows a burst of 5, then refuses", alice, List.of(true, true, true, true, true, false));
        clock.addAndGet(2_000_000_000L);
        List<Boolean> later = new ArrayList<>();
        for (int i = 0; i < 3; i++) later.add(limiter.tryAcquire("alice"));
        System.out.println("alice, 3 calls at t=2s : " + later);
        check("2 s at 1 token/s refills exactly 2 tokens", later, List.of(true, true, false));
        long bob = java.util.stream.IntStream.range(0, 5).filter(i -> limiter.tryAcquire("bob")).count();
        System.out.println("bob is independent    : " + bob + " of 5 allowed");
        check("keys don't share buckets", bob, 5L);
        AtomicInteger allowed = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int t = 0; t < 8; t++) pool.submit(() -> { for (int i = 0; i < 1000; i++) if (limiter.tryAcquire("carol")) allowed.incrementAndGet(); });
        }
        System.out.println("carol, 8 threads x 1000 calls at one instant: " + allowed.get() + " allowed");
        check("8,000 concurrent calls with a frozen clock never get more than the burst", allowed.get(), 5);
        clock.addAndGet(10_000_000_000L);
        int evicted = limiter.evictIdle();
        System.out.println("t=12s: evicted " + evicted + " idle buckets, tracking " + limiter.trackedKeys());
        check("idle, full buckets are evicted", evicted, 3);
        if (failures > 0) System.exit(1);
    }

    static int failures;

    /** Silent when the invariant holds, so the output stays as the chapter shows it. */
    static void check(String invariant, Object actual, Object expected) {
        if (!actual.equals(expected)) {
            failures++;
            System.out.println("CHECK FAILED: " + invariant + ": got " + actual + ", expected " + expected);
        }
    }
}
