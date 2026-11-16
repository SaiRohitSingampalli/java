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
