/*
 * Lab:       Colliding hash codes, HashMap's bit spreading and the bucket index
 * Chapters:  06 (collections)
 * Run:       java HashBuckets.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   OpenJDK source: java.util.HashMap.hash()
 * Output:    deterministic.
 */
public class HashBuckets {
    public static void main(String[] args) {
        for (String key : new String[]{"Aa", "BB", "C3", "id"}) {
            int h = key.hashCode();
            int spread = h ^ (h >>> 16);                   // HashMap.hash(): mix the high bits into the low bits
            System.out.printf("%-3s hashCode=%-5d bucket in a 16-slot table=%d%n", key, h, spread & 15);   // (n - 1) & hash
        }
    }
}
