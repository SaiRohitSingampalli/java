/*
 * Lab:       String identity: pooling, constant folding and intern()
 * Chapters:  03 (strings)
 * Run:       java StringEquality.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JLS §3.10.5 (string literals), §4.12.4 (constant variables), §15.29 (constant expressions)
 * Output:    deterministic.
 */
public class StringEquality {
    static final String FINAL_PART = "hel";            // constant variable

    public static void main(String[] args) {
        String a = "hello";
        String b = "hello";
        String c = new String("hello");
        String d = "hel" + "lo";                       // constant expression → folded by javac
        String part = "hel";
        String e = part + "lo";                        // part is NOT final → concatenated at runtime
        String f = FINAL_PART + "lo";                  // constant expression → folded
        String g = c.intern();

        System.out.println(a == b);       // true   both are the same pooled literal
        System.out.println(a == c);       // false  new always creates a new object
        System.out.println(a == d);       // true   javac turned d into the literal "hello"
        System.out.println(a == e);       // false  runtime concatenation creates a new object
        System.out.println(a == f);       // true   folded at compile time
        System.out.println(a == g);       // true   intern() returns the pooled instance
        System.out.println(a.equals(c));  // true   always compare content with equals
    }
}
