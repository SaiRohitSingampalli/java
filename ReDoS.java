/*
 * Lab:       Catastrophic backtracking in regular expressions, timed
 * Chapters:  03 (strings), 32 (case 8)
 * Run:       java ReDoS.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   OWASP, Regular expression Denial of Service: https://owasp.org/www-community/attacks/Regular_expression_Denial_of_Service_-_ReDoS
 * Output:    timings vary by machine. The last case takes about a minute.
 */
import java.util.regex.Pattern;
public class ReDoS {
    static void time(String label, String regex, String input) {
        long t = System.nanoTime();
        boolean m = Pattern.compile(regex).matcher(input).matches();
        System.out.printf("%-40s %8.2f s  (match=%b)%n", label, (System.nanoTime() - t) / 1e9, m);
    }
    public static void main(String[] a) {
        time("(a+)+b, 26 a's + '!'", "(a+)+b", "a".repeat(26) + "!");
        time("^(\\w+\\s?){1,100}$, 8 words + '!'", "^(\\w+\\s?){1,100}$", "aaaa ".repeat(8).trim() + "!");
        time("(a+){2,40}b, 28 a's + '!'", "(a+){2,40}b", "a".repeat(28) + "!");
        time("^(\\w+\\s?){1,100}$, 10 words + '!'", "^(\\w+\\s?){1,100}$", "aaaa ".repeat(10).trim() + "!");
    }
}
