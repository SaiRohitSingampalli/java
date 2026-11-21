/*
 * Lab:       Numeric traps: lossy widening, overflow, Math.abs(MIN_VALUE), shift masking, 0.1 + 0.2, NaN
 * Chapters:  01 (platform fundamentals)
 * Run:       java Numbers.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JLS §4.2.2 (integer operations), §5.1.2 (widening), §15.19 (shifts); IEEE 754
 * Output:    deterministic.
 */
public class Numbers {
    public static void main(String[] args) {
        int big = 123_456_789;
        float f = big;                                         // "widening", but float has a 24-bit significand
        show("(int) (float) 123_456_789", (int) f);
        show("Integer.MAX_VALUE + 1", Integer.MAX_VALUE + 1);
        show("Math.abs(Integer.MIN_VALUE)", Math.abs(Integer.MIN_VALUE));
        show("-Integer.MIN_VALUE == Integer.MIN_VALUE", -Integer.MIN_VALUE == Integer.MIN_VALUE);
        long wrong = 1_000_000 * 1_000_000;                   // multiplied as int, then widened
        long right = 1_000_000L * 1_000_000;
        show("long wrong = 1_000_000 * 1_000_000", wrong);
        show("long right = 1_000_000L * 1_000_000", right);
        show("(int) 3_000_000_000L", (int) 3_000_000_000L);
        show("1 << 31", 1 << 31);
        show("1 << 32  (shift distance masked to 0)", 1 << 32);
        show("1L << 32", 1L << 32);
        show("-8 >> 1  (arithmetic)", -8 >> 1);
        show("-8 >>> 1 (logical)", -8 >>> 1);
        show("-1 >>> 28", -1 >>> 28);
        show("0.1 + 0.2", 0.1 + 0.2);
        show("0.1 + 0.2 == 0.3", 0.1 + 0.2 == 0.3);
        show("9007199254740992.0 + 1 == 9007199254740992.0", 9007199254740992.0 + 1 == 9007199254740992.0);
        show("0.0 / 0", 0.0 / 0);
        show("Double.NaN == Double.NaN", Double.NaN == Double.NaN);
    }
    static void show(String expression, Object value) { System.out.printf("%-48s = %s%n", expression, value); }
}
