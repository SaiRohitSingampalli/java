import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

public class ConcurrencyProblems {
    // 1. round-robin printer, wait/notifyAll
    static final class RoundRobinPrinter {
        private final int threads, max;
        private int next = 1;                                   // guarded by this
        private final List<String> out = Collections.synchronizedList(new ArrayList<>());
        RoundRobinPrinter(int threads, int max) { this.threads = threads; this.max = max; }
        void run(int id) throws InterruptedException {
            while (true) {
                synchronized (this) {
                    while (next <= max && (next - 1) % threads != id) wait();
                    if (next > max) { notifyAll(); return; }
                    out.add(Thread.currentThread().getName() + ":" + next);
                    next++;
                    notifyAll();
                }
            }
        }
    }
    // 2. odd/even with semaphores
    static final class OddEvenPrinter {
        private final Semaphore oddTurn = new Semaphore(1), evenTurn = new Semaphore(0);
        private final int max;
        final List<Integer> out = Collections.synchronizedList(new ArrayList<>());
        OddEvenPrinter(int max) { this.max = max; }
        void printOdds() throws InterruptedException {
            for (int i = 1; i <= max; i += 2) { oddTurn.acquire(); out.add(i); evenTurn.release(); }
        }
        void printEvens() throws InterruptedException {
            for (int i = 2; i <= max; i += 2) { evenTurn.acquire(); out.add(i); oddTurn.release(); }
        }
    }
    // 3. bounded buffer
    static final class BoundedBuffer<E> {
        private final Object[] items;
        private int head, tail, count;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notFull = lock.newCondition(), notEmpty = lock.newCondition();
        BoundedBuffer(int capacity) { items = new Object[capacity]; }
        void put(E e) throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (count == items.length) notFull.await();
                items[tail] = e;
                tail = (tail + 1) % items.length;
                count++;
                notEmpty.signal();
            } finally { lock.unlock(); }
        }
        @SuppressWarnings("unchecked")
        E take() throws InterruptedException {
            lock.lockInterruptibly();
            try {
                while (count == 0) notEmpty.await();
                E e = (E) items[head];
                items[head] = null;
                head = (head + 1) % items.length;
                count--;
                notFull.signal();
                return e;
            } finally { lock.unlock(); }
        }
    }
    // 4. H2O
    static final class H2O {
        private final Semaphore hydrogen = new Semaphore(2), oxygen = new Semaphore(1);
        private final CyclicBarrier molecule = new CyclicBarrier(3);
        void hydrogen(Runnable releaseHydrogen) throws InterruptedException {
            hydrogen.acquire();
            try { awaitMolecule(); releaseHydrogen.run(); } finally { hydrogen.release(); }
        }
        void oxygen(Runnable releaseOxygen) throws InterruptedException {
            oxygen.acquire();
            try { awaitMolecule(); releaseOxygen.run(); } finally { oxygen.release(); }
        }
        private void awaitMolecule() throws InterruptedException {
            try { molecule.await(); } catch (BrokenBarrierException e) { throw new IllegalStateException(e); }
        }
    }
    // 5. dining philosophers
    static long dine(int n, int meals, boolean ordered, long timeoutMs) throws InterruptedException {
        ReentrantLock[] forks = new ReentrantLock[n];
        for (int i = 0; i < n; i++) forks[i] = new ReentrantLock();
        LongAdder eaten = new LongAdder();
        List<Thread> threads = new ArrayList<>();
        for (int p = 0; p < n; p++) {
            int left = p, right = (p + 1) % n;
            int first = ordered ? Math.min(left, right) : left;
            int second = ordered ? Math.max(left, right) : right;
            Thread t = Thread.ofPlatform().daemon().name("philosopher-" + p).start(() -> {
                for (int m = 0; m < meals; m++) {
                    forks[first].lock();
                    try {
                        forks[second].lock();
                        try { eaten.increment(); } finally { forks[second].unlock(); }
                    } finally { forks[first].unlock(); }
                }
            });
            threads.add(t);
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        for (Thread t : threads) t.join(Math.max(1, deadline - System.currentTimeMillis()));
        long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        Set<Long> mine = new HashSet<>();
        for (Thread t : threads) mine.add(t.threadId());
        long deadlocked = ids == null ? 0 : Arrays.stream(ids).filter(mine::contains).count();
        System.out.printf("  %-9s finished=%-5s meals eaten=%,d  deadlocked threads=%d%n",
                ordered ? "ordered:" : "naive:", threads.stream().noneMatch(Thread::isAlive), eaten.sum(), deadlocked);
        return eaten.sum();
    }
    // 6. thread pool
    static final class SimpleThreadPool implements AutoCloseable {
        private final BlockingQueue<Runnable> queue;
        private final List<Thread> workers = new ArrayList<>();
        private volatile boolean shutdown;
        SimpleThreadPool(int threads, int queueCapacity) {
            queue = new ArrayBlockingQueue<>(queueCapacity);
            for (int i = 0; i < threads; i++) workers.add(Thread.ofPlatform().name("pool-worker-" + i).start(this::workLoop));
        }
        synchronized void submit(Runnable task) {
            Objects.requireNonNull(task);
            if (shutdown) throw new RejectedExecutionException("pool is shut down");
            if (!queue.offer(task)) throw new RejectedExecutionException("queue is full");
        }
        private void workLoop() {
            while (true) {
                Runnable task;
                try { task = queue.poll(50, TimeUnit.MILLISECONDS); }
                catch (InterruptedException e) { return; }
                if (task != null) runSafely(task);
                else if (shutdown && queue.isEmpty()) return;
            }
        }
        private static void runSafely(Runnable task) {
            try { task.run(); } catch (RuntimeException e) { System.err.println("task failed: " + e); }
        }
        synchronized void shutdown() { shutdown = true; }
        synchronized List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>();
            queue.drainTo(pending);
            workers.forEach(Thread::interrupt);
            return pending;
        }
        boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(timeout);
            for (Thread w : workers) {
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remainingMs <= 0) return false;
                w.join(remainingMs);
                if (w.isAlive()) return false;
            }
            return true;
        }
        @Override public void close() throws InterruptedException { shutdown(); awaitTermination(1, TimeUnit.MINUTES); }
    }
    // 7. crawler
    interface HtmlParser { List<String> getUrls(String url); }
    static List<String> crawl(String startUrl, HtmlParser parser) {
        String host = URI.create(startUrl).getHost();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        seen.add(startUrl);
        AtomicInteger pending = new AtomicInteger();
        CompletableFuture<Void> done = new CompletableFuture<>();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            submit(startUrl, host, parser, seen, pending, done, exec);
            done.join();
        }
        return List.copyOf(seen);
    }
    private static void submit(String url, String host, HtmlParser parser, Set<String> seen,
                               AtomicInteger pending, CompletableFuture<Void> done, ExecutorService exec) {
        pending.incrementAndGet();
        exec.submit(() -> {
            try {
                for (String next : parser.getUrls(url)) {
                    if (host.equals(URI.create(next).getHost()) && seen.add(next)) {
                        submit(next, host, parser, seen, pending, done, exec);
                    }
                }
            } catch (RuntimeException e) {
                done.completeExceptionally(e);
            } finally {
                if (pending.decrementAndGet() == 0) done.complete(null);
            }
        });
    }

    public static void main(String[] args) throws Exception {
        // 1
        RoundRobinPrinter rr = new RoundRobinPrinter(3, 10);
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 3; i++) { int id = i; ts.add(Thread.ofPlatform().name("T" + i).start(() -> { try { rr.run(id); } catch (InterruptedException ignored) {} })); }
        for (Thread t : ts) t.join();
        System.out.println("round-robin: " + rr.out);
        // 2
        OddEvenPrinter oe = new OddEvenPrinter(20);
        Thread odd = Thread.ofPlatform().start(() -> { try { oe.printOdds(); } catch (InterruptedException ignored) {} });
        Thread even = Thread.ofPlatform().start(() -> { try { oe.printEvens(); } catch (InterruptedException ignored) {} });
        odd.join(); even.join();
        List<Integer> expected = new ArrayList<>(); for (int i = 1; i <= 20; i++) expected.add(i);
        System.out.println("odd/even in order 1..20: " + oe.out.equals(expected));
        // 3
        BoundedBuffer<Integer> buf = new BoundedBuffer<>(8);
        int producers = 4, perProducer = 50_000;
        LongAdder consumedSum = new LongAdder();
        AtomicInteger consumed = new AtomicInteger();
        List<Thread> all = new ArrayList<>();
        for (int p = 0; p < producers; p++) all.add(Thread.ofPlatform().start(() -> { try { for (int i = 1; i <= perProducer; i++) buf.put(i); } catch (InterruptedException ignored) {} }));
        for (int c = 0; c < 4; c++) all.add(Thread.ofPlatform().start(() -> { try { while (consumed.incrementAndGet() <= producers * perProducer) consumedSum.add(buf.take()); } catch (InterruptedException ignored) {} }));
        for (Thread t : all) t.join();
        long expectedSum = (long) producers * perProducer * (perProducer + 1) / 2;
        System.out.println("bounded buffer sum ok: " + (consumedSum.sum() == expectedSum) + " (" + consumedSum.sum() + ")");
        // 4
        H2O h2o = new H2O();
        StringBuffer molecules = new StringBuffer();
        String atoms = "HHO".repeat(200);
        List<Character> shuffled = new ArrayList<>(); for (char ch : atoms.toCharArray()) shuffled.add(ch);
        Collections.shuffle(shuffled, new Random(42));
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (char ch : shuffled) {
                if (ch == 'H') ex.submit(() -> { h2o.hydrogen(() -> molecules.append('H')); return null; });
                else ex.submit(() -> { h2o.oxygen(() -> molecules.append('O')); return null; });
            }
        }
        boolean valid = molecules.length() == 600;
        for (int i = 0; i < molecules.length(); i += 3) {
            String group = molecules.substring(i, i + 3);
            if (group.chars().filter(x -> x == 'H').count() != 2) { valid = false; break; }
        }
        System.out.println("H2O: " + molecules.length() / 3 + " molecules, every group of 3 = 2H+1O: " + valid + "  first 12: " + molecules.substring(0, 12));
        // 5
        System.out.println("dining philosophers (5 philosophers x 1,000,000 meals each):");
        dine(5, 1_000_000, false, 3000);
        dine(5, 1_000_000, true, 60000);
        // 6
        AtomicInteger ran = new AtomicInteger();
        try (SimpleThreadPool pool = new SimpleThreadPool(4, 2000)) {
            for (int i = 0; i < 1000; i++) pool.submit(ran::incrementAndGet);
        }
        System.out.println("thread pool ran " + ran.get() + " of 1000 tasks");
        SimpleThreadPool tiny = new SimpleThreadPool(1, 1);
        CountDownLatch block = new CountDownLatch(1);
        tiny.submit(() -> { try { block.await(); } catch (InterruptedException ignored) {} });
        Thread.sleep(100);
        tiny.submit(() -> {});
        try { tiny.submit(() -> {}); } catch (RejectedExecutionException e) { System.out.println("full queue -> " + e.getMessage()); }
        block.countDown(); tiny.shutdown();
        System.out.println("tiny pool terminated: " + tiny.awaitTermination(5, TimeUnit.SECONDS));
        // 7
        Map<String, List<String>> web = new HashMap<>();
        for (int i = 0; i < 2000; i++) {
            List<String> links = new ArrayList<>();
            links.add("http://news.example.com/p" + ((i * 7 + 1) % 2000));
            links.add("http://news.example.com/p" + ((i * 13 + 5) % 2000));
            links.add("http://other.example.org/x" + i);
            web.put("http://news.example.com/p" + i, links);
        }
        HtmlParser slowParser = url -> {
            try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return web.getOrDefault(url, List.of());
        };
        long start = System.nanoTime();
        List<String> found = crawl("http://news.example.com/p0", slowParser);
        System.out.printf("crawler: %d pages on news.example.com in %d ms (each fetch sleeps 20 ms; sequential would be ~%d ms)%n",
                found.size(), (System.nanoTime() - start) / 1_000_000, found.size() * 20);
    }
}
