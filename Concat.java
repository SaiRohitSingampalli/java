/*
 * Lab:       String concatenation compiles to one invokedynamic call
 * Chapters:  03 (strings)
 * Run:       javac -d /tmp/ch03 Concat.java && javap -c -v -cp /tmp/ch03 Concat | grep -A2 makeConcat
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JEP 280 (indify string concatenation): https://openjdk.org/jeps/280
 * Output:    deterministic.
 */
public class Concat {
    static String concat(String a, String b, String c) { return a + b + c; }   // javap -c -v: invokedynamic makeConcatWithConstants
    public static void main(String[] args) { System.out.println(concat("a", "b", "c")); }
}
