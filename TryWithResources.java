/*
 * Lab:       try-with-resources: reverse close order and suppressed exceptions
 * Chapters:  04 (exceptions)
 * Run:       java TryWithResources.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JLS §14.20.3 (try-with-resources)
 * Output:    deterministic.
 */
public class TryWithResources {
    public static void main(String[] args) {
        try (var a = new Res("A"); var b = new Res("B")) {
            throw new RuntimeException("body");
        } catch (RuntimeException e) {
            System.out.println("caught: " + e.getMessage());
            for (Throwable s : e.getSuppressed()) System.out.println("  suppressed: " + s.getMessage());
        }
    }
}

class Res implements AutoCloseable {
    private final String name;
    Res(String name) { this.name = name; System.out.println("open " + name); }
    @Override public void close() {
        System.out.println("close " + name);
        throw new IllegalStateException("close " + name);
    }
}
