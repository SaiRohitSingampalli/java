import java.time.Duration;
import java.util.concurrent.*;
import java.util.stream.IntStream;
public class VirtualThreads {
    public static void main(String[] args) throws Exception {
        for (int n : new int[]{10_000, 100_000}) {
            long t0 = System.nanoTime();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                IntStream.range(0, n).forEach(i -> executor.submit(() -> {
                    Thread.sleep(Duration.ofSeconds(1));
                    return i;
                }));
            }
            System.out.printf("%,d virtual threads each sleeping 1s: %d ms%n", n, (System.nanoTime() - t0) / 1_000_000);
        }
        long t0 = System.nanoTime();
        try (var pool = Executors.newFixedThreadPool(200)) {
            IntStream.range(0, 2_000).forEach(i -> pool.submit(() -> { Thread.sleep(1000); return i; }));
        }
        System.out.printf("2,000 tasks on 200 platform threads: %d ms%n", (System.nanoTime() - t0) / 1_000_000);
        System.out.println("cores=" + Runtime.getRuntime().availableProcessors());
    }
}
