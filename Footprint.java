/*
 * Lab:       Bytes per object with and without compact object headers
 * Chapters:  30 (startup and footprint)
 * Run:       java -Xmx2g -XX:-UseCompactObjectHeaders Footprint.java, then the same with -XX:+UseCompactObjectHeaders
 * Needs:     JDK 25 or newer (the flag is a product option from JDK 25)
 * Verified:  JDK 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JEP 519 (compact object headers): https://openjdk.org/jeps/519
 * Output:    bytes per object are stable to about 0.1; total heap varies slightly.
 */
import java.lang.management.ManagementFactory;
public class Footprint {
    record Point(int x, int y) {}
    record OrderLine(String sku, int qty, long priceCents) {}
    static long used() { System.gc(); System.gc(); return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
    public static void main(String[] args) {
        int n = 5_000_000;
        String sku = "SKU-1";
        long base = used();
        Object[] points = new Object[n];
        for (int i = 0; i < n; i++) points[i] = new Point(i, -i);
        long afterPoints = used();
        Object[] lines = new Object[n];
        for (int i = 0; i < n; i++) lines[i] = new OrderLine(sku, i & 7, i * 3L);
        long afterLines = used();
        long arrayBytes = 16L + 4L * n;   // compressed references
        System.out.printf("Point(int,int):          %5.1f bytes/object%n", (afterPoints - base - arrayBytes) / (double) n);
        System.out.printf("OrderLine(String,int,long): %5.1f bytes/object%n", (afterLines - afterPoints - arrayBytes) / (double) n);
        System.out.printf("total heap used: %d MB%n", (afterLines - base) >> 20);
        if (points.length + lines.length == 0) System.out.println();
    }
}
