/*
 * Lab:       count++ from two threads loses updates
 * Chapters:  11 (concurrency foundations)
 * Run:       java LostUpdate.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JLS §17.4 (the memory model)
 * Output:    varies from run to run: it's a data race. Run it a few times.
 */
public class LostUpdate {
    static int count = 0;
    public static void main(String[] args) throws InterruptedException {
        Runnable work = () -> { for (int i = 0; i < 1_000_000; i++) count++; };
        Thread t1 = new Thread(work), t2 = new Thread(work);
        t1.start(); t2.start(); t1.join(); t2.join();
        System.out.println("expected 2000000, got " + count);
    }
}
