/*
 * Lab:       One thread at 100% CPU, found with top -H and a thread dump
 * Chapters:  32 (case 8)
 * Run:       java HotThread.java, then top -H -p <pid> and jcmd <pid> Thread.print
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   the jcmd manual; Chapter 03 for the regular expression
 * Output:    busy for two minutes by design, then exits 0.
 */
import java.util.regex.Pattern;
public class HotThread {
    public static void main(String[] args) throws Exception {
        Pattern p = Pattern.compile("^(\\w+\\s?){1,100}$");                  // catastrophic backtracking (Chapter 03)
        Thread.ofPlatform().name("order-validator-1").start(() -> {
            while (true) p.matcher("pay the invoice for order number twelve now please ok!").matches();
        });
        for (int i = 0; i < 3; i++) Thread.ofPlatform().name("http-worker-" + i).start(() -> { while (true) { try { Thread.sleep(1000); } catch (InterruptedException e) { return; } } });
        Thread.sleep(120_000);
        System.exit(0);                  // the busy threads never finish on their own, so end the demo here
    }
}
