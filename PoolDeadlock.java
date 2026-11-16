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
    }
}
