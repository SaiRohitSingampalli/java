/*
 * Lab:       The Reactive Streams protocol traced with java.util.concurrent.Flow: backpressure, hot vs cold, a blocked event loop
 * Chapters:  29 (reactive Java)
 * Run:       java FlowDemos.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   Reactive Streams specification: https://www.reactive-streams.org/; java.util.concurrent.Flow and SubmissionPublisher API docs
 * Output:    deterministic, except counts that depend on timing (how many items offer() drops).
 */
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

public class FlowDemos {
    static void log(String msg) { System.out.printf("%-6s %s%n", Thread.currentThread().getName(), msg); }

    // ---------- Demo 1: the protocol, traced
    static final class Range implements Publisher<Integer> {
        private final int start, end;
        Range(int start, int count) { this.start = start; this.end = start + count; }
        @Override public void subscribe(Subscriber<? super Integer> s) {
            log("Range: subscribed, handing out a Subscription");
            s.onSubscribe(new Subscription() {
                private final AtomicLong requested = new AtomicLong();
                private final AtomicInteger wip = new AtomicInteger();
                private int next = start;
                private volatile boolean done;
                @Override public void request(long n) {
                    log("Range: request(" + n + ")");
                    if (n <= 0) { cancel(); s.onError(new IllegalArgumentException("Rule 3.9: request(n) needs n > 0")); return; }
                    requested.getAndUpdate(r -> r + n < 0 ? Long.MAX_VALUE : r + n);
                    if (wip.getAndIncrement() != 0) return;          // already emitting: the outer loop will see the new demand
                    int missed = 1;
                    for (;;) {
                        long r = requested.get(), emitted = 0;
                        while (emitted != r && next < end) {
                            if (done) return;
                            s.onNext(next++);
                            emitted++;
                        }
                        if (next == end) { if (!done) { done = true; s.onComplete(); } return; }
                        if (r != Long.MAX_VALUE) requested.addAndGet(-emitted);
                        missed = wip.addAndGet(-missed);
                        if (missed == 0) return;
                    }
                }
                @Override public void cancel() { done = true; }
            });
        }
    }
    static final class MapOp<T, R> implements Publisher<R> {
        private final Publisher<T> upstream; private final Function<T, R> fn; private final String name;
        MapOp(Publisher<T> upstream, Function<T, R> fn, String name) { this.upstream = upstream; this.fn = fn; this.name = name; }
        @Override public void subscribe(Subscriber<? super R> downstream) {
            log(name + ": subscribed, subscribing to upstream");
            upstream.subscribe(new Subscriber<T>() {
                @Override public void onSubscribe(Subscription s) { log(name + ": onSubscribe passes downstream"); downstream.onSubscribe(s); }
                @Override public void onNext(T item) { downstream.onNext(fn.apply(item)); }
                @Override public void onError(Throwable t) { downstream.onError(t); }
                @Override public void onComplete() { downstream.onComplete(); }
            });
        }
    }
    static final class Printer implements Subscriber<String> {
        private Subscription s; private int received;
        @Override public void onSubscribe(Subscription s) { this.s = s; log("Printer: onSubscribe, request(2)"); s.request(2); }
        @Override public void onNext(String item) {
            log("Printer: onNext(" + item + ")");
            if (++received % 2 == 0) { log("Printer: request(2)"); s.request(2); }
        }
        @Override public void onError(Throwable t) { log("Printer: onError " + t); }
        @Override public void onComplete() { log("Printer: onComplete"); }
    }

    // ---------- Demo 2: backpressure with SubmissionPublisher
    static final class SlowSubscriber implements Subscriber<Integer> {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger received = new AtomicInteger();
        private Subscription s;
        @Override public void onSubscribe(Subscription s) { this.s = s; s.request(1); }
        @Override public void onNext(Integer item) { sleep(5); received.incrementAndGet(); s.request(1); }
        @Override public void onError(Throwable t) { done.countDown(); }
        @Override public void onComplete() { done.countDown(); }
    }
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }

    static void backpressure() throws Exception {
        ExecutorService consumerThread = Executors.newSingleThreadExecutor();
        try (var pub = new SubmissionPublisher<Integer>(consumerThread, 8)) {
            var sub = new SlowSubscriber();
            pub.subscribe(sub);
            AtomicInteger dropped = new AtomicInteger();
            long t0 = System.nanoTime();
            for (int i = 0; i < 500; i++) pub.offer(i, (subscriber, item) -> { dropped.incrementAndGet(); return false; });
            long offerMs = (System.nanoTime() - t0) / 1_000_000;
            pub.close();
            sub.done.await();
            System.out.printf("offer():  producer finished in %d ms; consumer received %d, dropped %d%n", offerMs, sub.received.get(), dropped.get());
        }
        try (var pub = new SubmissionPublisher<Integer>(consumerThread, 8)) {
            var sub = new SlowSubscriber();
            pub.subscribe(sub);
            long t0 = System.nanoTime();
            for (int i = 0; i < 200; i++) pub.submit(i);
            long submitMs = (System.nanoTime() - t0) / 1_000_000;
            pub.close();
            sub.done.await();
            System.out.printf("submit(): producer needed %d ms to hand off 200 items; consumer received %d, dropped 0%n", submitMs, sub.received.get());
        }
        consumerThread.shutdown();
    }

    // ---------- Demo 3: cold vs hot
    static void coldVsHot() throws Exception {
        List<Integer> a = Collections.synchronizedList(new ArrayList<>()), b = Collections.synchronizedList(new ArrayList<>());
        Range cold = new Range(1, 3);
        cold.subscribe(collector(a, null));
        cold.subscribe(collector(b, null));
        System.out.println("cold: A=" + a + " B=" + b + "   (each subscriber gets its own run from the start)");
        List<Integer> early = Collections.synchronizedList(new ArrayList<>()), late = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(2);
        try (var hot = new SubmissionPublisher<Integer>()) {
            hot.subscribe(collector(early, done));
            for (int i = 1; i <= 5; i++) hot.submit(i);
            hot.subscribe(collector(late, done));
            for (int i = 6; i <= 8; i++) hot.submit(i);
        }
        done.await();
        System.out.println("hot:  early=" + early + " late=" + late + "   (a late subscriber only sees what's emitted after it joins)");
    }
    static <T> Subscriber<T> collector(List<T> sink, CountDownLatch done) {
        return new Subscriber<T>() {
            public void onSubscribe(Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(T item) { sink.add(item); }
            public void onError(Throwable t) { if (done != null) done.countDown(); }
            public void onComplete() { if (done != null) done.countDown(); }
        };
    }

    // ---------- Demo 4: one blocking call on an event loop
    static void eventLoop() throws Exception {
        for (String mode : List.of("all non-blocking", "one blocking call ON the loop", "same call offloaded")) {
            ExecutorService loop = Executors.newSingleThreadExecutor(r -> new Thread(r, "event-loop"));
            ExecutorService elastic = Executors.newCachedThreadPool();
            int requests = 1000;
            long[] latency = new long[requests];
            CountDownLatch done = new CountDownLatch(requests);
            for (int i = 0; i < requests; i++) {
                int id = i;
                long arrived = System.nanoTime();
                if (id == 100 && mode.contains("ON")) loop.execute(() -> sleep(200));                              // e.g. a JDBC call
                if (id == 100 && mode.contains("offloaded")) loop.execute(() -> elastic.execute(() -> sleep(200))); // hand off, return at once
                loop.execute(() -> {
                    long x = 0; for (int k = 0; k < 20_000; k++) x += k ^ id;                                     // ~tens of µs of real work
                    if (x == 42) System.out.print("");
                    latency[id] = System.nanoTime() - arrived;
                    done.countDown();
                });
                LockSupport.parkNanos(1_000_000);                                                                    // ~1 request per ms
            }
            done.await();
            loop.shutdown(); elastic.shutdown();
            long[] sorted = latency.clone(); Arrays.sort(sorted);
            long delayed = Arrays.stream(latency).filter(l -> l > 10_000_000).count();
            System.out.printf("%-30s p50=%6.2f ms  p99=%7.2f ms  max=%7.2f ms  requests delayed >10 ms: %d%n", mode,
                    sorted[requests / 2] / 1e6, sorted[requests * 99 / 100] / 1e6, sorted[requests - 1] / 1e6, delayed);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("== 1. protocol trace ==");
        log("assembling Range(1..5) -> map1(x * 10) -> map2(\"#\" + x)");
        Publisher<String> pipeline = new MapOp<>(new MapOp<>(new Range(1, 5), x -> x * 10, "map1"), x -> "#" + x, "map2");
        log("assembled: nothing has executed yet");
        pipeline.subscribe(new Printer());
        System.out.println("\n== 2. backpressure ==");
        backpressure();
        System.out.println("\n== 3. cold vs hot ==");
        coldVsHot();
        System.out.println("\n== 4. event loop ==");
        eventLoop();
    }
}
