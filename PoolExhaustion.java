import java.util.*;
import java.util.concurrent.*;
public class PoolExhaustion {
    static final Semaphore pool = new Semaphore(10);                     // a 10-connection pool
    static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException e) { throw new RuntimeException(e); } }
    static void db() { sleep(10); }                                       // 10 ms of real database work
    static void remoteCall() { sleep(200); }                              // 200 ms call to another service

    static long[] handle(boolean remoteInsideTx) throws InterruptedException {
        long t0 = System.nanoTime();
        if (!remoteInsideTx) remoteCall();                                // call the other service BEFORE taking a connection
        if (!pool.tryAcquire(1, TimeUnit.SECONDS)) return new long[]{-1, (System.nanoTime() - t0) / 1_000_000};   // pool timeout
        try {
            db();
            if (remoteInsideTx) remoteCall();                             // @Transactional method that also calls a remote service
            db();
        } finally { pool.release(); }
        return new long[]{1, (System.nanoTime() - t0) / 1_000_000};
    }

    public static void main(String[] args) throws Exception {
        for (boolean inside : new boolean[]{true, false}) {
            List<Future<long[]>> results = new ArrayList<>();
            try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int i = 0; i < 300; i++) {                           // 100 requests/s for 3 s
                    results.add(exec.submit(() -> handle(inside)));
                    Thread.sleep(10);
                }
            }
            long ok = 0, timedOut = 0; List<Long> lat = new ArrayList<>();
            for (Future<long[]> f : results) { long[] r = f.get(); if (r[0] == 1) { ok++; lat.add(r[1]); } else timedOut++; }
            Collections.sort(lat);
            System.out.printf("remote call %-26s ok=%3d  pool timeouts=%3d  p50=%5d ms  p99=%5d ms%n",
                    inside ? "INSIDE the transaction:" : "before taking a connection:", ok, timedOut,
                    lat.isEmpty() ? 0 : lat.get(lat.size() / 2), lat.isEmpty() ? 0 : lat.get(lat.size() * 99 / 100));
        }
    }
}
