/*
 * Lab:       Thread-starvation deadlock: parent tasks waiting on child tasks in the same pool
 * Chapters:  32 (case 2)
 * Run:       java PoolDeadlock.java, then jcmd <pid> Thread.print while it hangs
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   Brian Goetz et al., Java Concurrency in Practice §8.1.1 (thread-starvation deadlock)
 * Output:    hangs for 60 seconds by design so you can inspect it, then exits 0.
 */
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public class PoolDeadlock {
    public static void main(String[] args) throws Exception {
        AtomicInteger n = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(4, r -> new Thread(r, "orders-pool-" + n.incrementAndGet()));
        List<Future<String>> requests = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            int id = r;
            requests.add(pool.submit(() -> {
                Future<String> price = pool.submit(() -> "price-" + id);    // child task on the SAME pool
                Future<String> stock = pool.submit(() -> "stock-" + id);
                return price.get() + "/" + stock.get();                     // the parent blocks a pool thread waiting for its children
            }));
        }
        Thread.sleep(1000);
        System.out.println("requests done after 1 s: " + requests.stream().map(Future::isDone).toList());
        Thread.sleep(60_000);
        System.exit(0);                  // the pool's threads stay blocked forever, so end the demo here
    }
}
