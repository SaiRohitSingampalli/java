/*
 * Lab:       The GC death spiral: a heap that's almost full
 * Chapters:  32 (case 6)
 * Run:       java -Xmx128m -Xlog:gc:file=gc.log GcThrash.java, then read gc.log
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   HotSpot Virtual Machine Garbage Collection Tuning Guide
 * Output:    runs until OutOfMemoryError (about 5 s at -Xmx128m), reports it and exits 0. Leaves gc.log behind.
 */
import java.util.*;
public class GcThrash {
    static volatile Object sink;                                                  // keeps the JIT from eliminating the garbage
    public static void main(String[] args) {
        List<byte[]> leak = new ArrayList<>();
        long t0 = System.nanoTime();
        try {
            while (true) {
                leak.add(new byte[32 * 1024]);                                   // live set grows slowly (the leak)
                for (int i = 0; i < 100; i++) sink = new byte[32 * 1024];        // normal per-request garbage
            }
        } catch (OutOfMemoryError e) {
            int retained = leak.size();
            leak = null;
            System.out.printf("%s after %d ms, live set ~%d MB%n", e, (System.nanoTime() - t0) / 1_000_000, retained * 32 / 1024);
        }
    }
}
