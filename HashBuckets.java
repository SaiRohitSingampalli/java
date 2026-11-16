public class HashBuckets {
    public static void main(String[] args) {
        for (String key : new String[]{"Aa", "BB", "C3", "id"}) {
            int h = key.hashCode();
            int spread = h ^ (h >>> 16);                   // HashMap.hash(): mix the high bits into the low bits
            System.out.printf("%-3s hashCode=%-5d bucket in a 16-slot table=%d%n", key, h, spread & 15);   // (n - 1) & hash
        }
    }
}
