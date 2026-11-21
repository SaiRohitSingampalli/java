/*
 * Lab:       Short-link codes: how many characters an ID needs, and why encoding isn't protection
 * Chapters:  24 §7 (URL shortener)
 * Run:       java ShortCodes.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   NIST SP 800-38G (format-preserving encryption): https://csrc.nist.gov/pubs/sp/800/38/g/final
 * Output:    deterministic (fixed key and fixed date). Exits with status 1 if a check fails.
 */
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class ShortCodes {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final long SEVEN = 3_521_614_606_208L;                      // 62^7

    public static void main(String[] args) throws Exception {
        boolean ok = true;
        BigInteger base = BigInteger.valueOf(62);

        System.out.println("1. How many IDs fit in n base62 characters");
        System.out.printf("   %5s  %32s%n", "chars", "distinct codes");
        for (int n : new int[] {6, 7, 8, 10, 11}) {
            BigInteger codes = base.pow(n);
            String note = n == 10 ? "   < 2^63" : n == 11 ? "   > 2^63 - 1 = " + String.format("%,d", Long.MAX_VALUE) : "";
            System.out.printf("   %5d  %32s%s%n", n, String.format("%,d", codes), note);
        }
        ok &= check(base.pow(7).longValueExact() == SEVEN, "62^7 constant");
        long epoch = Instant.parse("2020-01-01T00:00:00Z").toEpochMilli();
        long minted = Instant.parse("2026-11-18T12:00:00Z").toEpochMilli();
        long snowflake = ((minted - epoch) << 22) | (37L << 12) | 5;    // 41-bit ms, 10-bit worker 37, 12-bit sequence 5
        System.out.printf("   a Snowflake ID minted on 2026-11-18 (ms since 2020-01-01, worker 37, sequence 5):%n");
        System.out.printf("     %,d -> %s (%d characters)%n", snowflake, encode(snowflake, 0), encode(snowflake, 0).length());
        System.out.printf("     the largest 64-bit ID, %,d -> %s (%d characters)%n", Long.MAX_VALUE, encode(Long.MAX_VALUE, 0),
                          encode(Long.MAX_VALUE, 0).length());
        for (int perSecond : new int[] {10_000, 1_000}) {
            double years = SEVEN / (double) perSecond / (365.25 * 24 * 3600);
            System.out.printf("   7 characters last %.1f years at a sustained %,d creates per second%n", years, perSecond);
        }

        System.out.println();
        System.out.println("2. Base62 of a counter is still a counter: neighbours are one character apart");
        for (long id = 1_000_000_000L; id < 1_000_000_004L; id++)
            System.out.printf("   id %,d -> %s%n", id, encode(id, 7));

        System.out.println();
        System.out.println("3. The same counter through a keyed permutation of [0, 62^7) (Feistel, 6 rounds, cycle-walking)");
        Feistel permutation = new Feistel("demo key: keep the real one in a secrets store".getBytes(StandardCharsets.UTF_8));
        for (long id = 1_000_000_000L; id < 1_000_000_004L; id++)
            System.out.printf("   id %,d -> %s%n", id, encode(permutation.encrypt(id), 7));
        int sample = 200_000;
        Set<Long> seen = new HashSet<>();
        boolean roundTrip = true;
        for (long id = 1_000_000_000L; id < 1_000_000_000L + sample; id++) {
            long code = permutation.encrypt(id);
            roundTrip &= code >= 0 && code < SEVEN && permutation.decrypt(code) == id;
            seen.add(code);
        }
        System.out.printf("   %,d consecutive ids -> %,d distinct 7-character codes; every code decodes to its id: %s%n",
                          sample, seen.size(), roundTrip ? "yes" : "NO");
        ok &= check(seen.size() == sample && roundTrip, "permutation is a bijection on the sample");

        System.out.println();
        System.out.println("4. Random 7-character codes: chance that a new code hits an existing one (insert, retry on conflict)");
        System.out.printf("   %15s  %s%n", "links stored", "collision chance per insert");
        for (long stored : new long[] {1_000_000L, 100_000_000L, 1_000_000_000L, 100_000_000_000L})
            System.out.printf("   %,15d  %.5f%%%n", stored, 100.0 * stored / SEVEN);

        System.out.println();
        System.out.println("   Neither 3 nor 4 makes a link secret: anyone holding the code can follow it.");
        System.out.println("   Private links need a long random token (128 bits = 22 base62 characters) and access checks.");
        if (!ok) System.exit(1);
    }

    /** Base62, left-padded with '0' to {@code width} characters. */
    static String encode(long value, int width) {
        StringBuilder sb = new StringBuilder();
        BigInteger v = new BigInteger(Long.toUnsignedString(value)), base = BigInteger.valueOf(62);
        do {
            BigInteger[] qr = v.divideAndRemainder(base);
            sb.append(ALPHABET.charAt(qr[1].intValue()));
            v = qr[0];
        } while (v.signum() > 0);
        while (sb.length() < width) sb.append('0');
        return sb.reverse().toString();
    }

    /**
     * A keyed permutation of [0, 62^7): a balanced Feistel network on 42 bits (two 21-bit halves) with
     * HMAC-SHA256 as the round function, plus cycle-walking to stay inside the domain. It shows the idea;
     * in production use a standard format-preserving cipher such as NIST FF1.
     */
    static final class Feistel {
        private static final int HALF = 21, ROUNDS = 6;
        private static final long MASK = (1L << HALF) - 1;
        private final Mac mac;

        Feistel(byte[] key) throws Exception {
            mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
        }

        long encrypt(long x) { do x = rounds(x, true); while (x >= SEVEN); return x; }
        long decrypt(long x) { do x = rounds(x, false); while (x >= SEVEN); return x; }

        private long rounds(long x, boolean forward) {
            long left = x >>> HALF, right = x & MASK;
            for (int i = 0; i < ROUNDS; i++) {
                int round = forward ? i : ROUNDS - 1 - i;
                if (forward) { long next = left ^ f(round, right); left = right; right = next; }
                else         { long previous = right ^ f(round, left); right = left; left = previous; }
            }
            return (left << HALF) | right;
        }

        private long f(int round, long half) {
            byte[] digest = mac.doFinal(ByteBuffer.allocate(12).putInt(round).putLong(half).array());
            return ByteBuffer.wrap(digest).getLong() & MASK;
        }
    }

    static boolean check(boolean condition, String what) {
        if (!condition) System.out.println("CHECK FAILED: " + what);
        return condition;
    }
}
