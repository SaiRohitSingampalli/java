/*
 * Lab:       A non-volatile flag the worker thread never sees once the JIT has compiled its loop
 * Chapters:  11 (concurrency foundations)
 * Run:       java StopFlag.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JLS §17.4 (the memory model)
 * Output:    usually reports that the worker never saw the write; it gives up after about 4 s and exits 0.
 */
public class StopFlag {
    static boolean stop = false;                           // NOT volatile
    public static void main(String[] args) throws Exception {
        Thread worker = new Thread(() -> {
            long spins = 0;
            while (!stop) { spins++; }                     // the JIT hoists the read: if (!stop) while (true) …
            System.out.println("worker saw stop after " + spins + " spins");
        });
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(1000);
        stop = true;
        System.out.println("main set stop = true");
        worker.join(3000);
        System.out.println(worker.isAlive() ? "worker is STILL running (never saw the write)" : "worker exited");
    }
}
