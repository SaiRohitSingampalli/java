/*
 * Lab:       Locale data drift: what a JDK upgrade does to strings your service writes and reads back
 * Chapters:  37 (fleet JDK upgrade: rollback limits), 03 (strings)
 * Run:       java LocaleDrift.java
 *            java -Djava.locale.providers=COMPAT LocaleDrift.java      (run both on each JDK you're moving between)
 * Needs:     JDK 17 or newer, nothing else
 * Verified:  JDK 17.0.21, 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JDK-8284840, CLDR 42 in JDK 20 (narrow no-break space before AM/PM): https://bugs.openjdk.org/browse/JDK-8284840
 *            JDK-8325568, removal of COMPAT locale data in JDK 23: https://bugs.openjdk.org/browse/JDK-8325568
 *            JDK-8324990, lenient parsing treats all space separators alike from JDK 23: https://bugs.openjdk.org/browse/JDK-8324990
 *            JEP 252, CLDR locale data by default: https://openjdk.org/jeps/252
 * Output:    deterministic for a given JDK and flag. Exits 0.
 */
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.Locale;

public class LocaleDrift {

    public static void main(String[] args) {
        DateTimeFormatter strict = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.US);
        DateTimeFormatter lenient = new DateTimeFormatterBuilder().parseLenient()
                .appendLocalized(null, FormatStyle.SHORT).toFormatter(Locale.US);
        String written = strict.format(LocalTime.of(15, 30));
        System.out.printf("JDK %d, java.locale.providers=%s: 15:30 is written as %s%n", Runtime.version().feature(),
                          System.getProperty("java.locale.providers", "(default)"), visible(written));
        for (String stored : new String[] {"3:30 PM", "3:30\u202FPM"}) {
            System.out.printf("  reading %-17s strict: %-18s lenient: %s%n", visible(stored),
                              parse(stored, strict), parse(stored, lenient));
        }
    }

    static String parse(String text, DateTimeFormatter formatter) {
        try {
            return "ok, " + LocalTime.parse(text, formatter);
        } catch (DateTimeParseException e) {
            return "FAILS at index " + e.getErrorIndex();
        }
    }

    /** Shows spaces and other invisible characters as code points. */
    static String visible(String s) {
        StringBuilder sb = new StringBuilder("\"");
        s.codePoints().forEach(cp -> sb.append(cp > ' ' && cp < 127 ? String.valueOf((char) cp) : String.format("[U+%04X]", cp)));
        return sb.append('"').toString();
    }
}
