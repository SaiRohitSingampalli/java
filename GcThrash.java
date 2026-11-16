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
