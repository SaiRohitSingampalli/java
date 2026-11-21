/*
 * Lab:       The default charset: what a service reads from a UTF-8 file when its container sets no locale
 * Chapters:  37 (fleet JDK upgrade, section 3.4), 03 (strings)
 * Run:       env -u LANG -u LC_ALL -u LC_CTYPE java DefaultCharset.java          (no locale, as in many containers)
 *            env -u LC_ALL -u LC_CTYPE LANG=C.UTF-8 java DefaultCharset.java    (a UTF-8 locale)
 *            on JDK 17 and on JDK 18 or later. On macOS the default is UTF-8 even on JDK 17, so run it on Linux.
 * Needs:     JDK 17 or newer, nothing else
 * Verified:  JDK 17.0.21, 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JEP 400 (UTF-8 by default, JDK 18): https://openjdk.org/jeps/400
 * Output:    deterministic for a given JDK and locale setting. Non-ASCII characters print as [U+XXXX], and the
 *            source itself is ASCII, so the compiler's own default charset can't change the text being written.
 */
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class DefaultCharset {

    public static void main(String[] args) throws IOException {
        Path file = Files.createTempFile("default-charset", ".txt");
        Files.writeString(file, "Caf\u00E9 at 15:30, 20 \u20AC", StandardCharsets.UTF_8);   // written as UTF-8, like most files
        try {
            System.out.printf("JDK %d, LANG=%s: default charset %s (native.encoding=%s)%n", Runtime.version().feature(),
                              System.getenv().getOrDefault("LANG", "(unset)"), Charset.defaultCharset(),
                              System.getProperty("native.encoding"));
            try (BufferedReader reader = new BufferedReader(new FileReader(file.toFile()))) {   // no charset: the default
                System.out.println("  new FileReader(file), default charset: " + visible(reader.readLine()));
            }
            System.out.println("  Files.readString(file), always UTF-8: " + visible(Files.readString(file)));
            try (FileWriter writer = new FileWriter(file.toFile())) {                         // no charset: the default
                writer.write("Caf\u00E9 at 15:30, 20 \u20AC");
            }
            System.out.println("  written by new FileWriter(file), read back as UTF-8: " + visible(Files.readString(file)));
        } finally {
            Files.delete(file);
        }
    }

    /** Plain ASCII output whatever the terminal: anything else is shown as its code point. */
    static String visible(String s) {
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(c -> out.append(c < 128 ? Character.toString(c) : String.format("[U+%04X]", c)));
        return out.toString();
    }
}
