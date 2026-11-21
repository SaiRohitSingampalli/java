/*
 * Lab:       Young collections in a G1 log
 * Chapters:  10 (memory and GC)
 * Run:       java -Xmx64m -Xlog:gc* Churn.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JEP 158 (unified JVM logging); HotSpot Virtual Machine Garbage Collection Tuning Guide
 * Output:    a GC log; the number and timing of collections vary by machine and JDK.
 */
import java.util.*;
public class Churn {
    public static void main(String[] a) throws Exception {
        List<byte[]> retained = new ArrayList<>();
        long sink = 0;
        for (int i = 0; i < 400_000; i++) {
            byte[] garbage = new byte[2048];            // short-lived
            sink += garbage.length;
            if (i % 50 == 0) retained.add(new byte[1024]);  // slowly growing live set
            if (retained.size() > 4000) retained.subList(0, 2000).clear();
        }
        System.out.println("done " + sink + " " + retained.size());
    }
}
