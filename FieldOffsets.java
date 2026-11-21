/*
 * Lab:       Field layout and object header size, read through sun.misc.Unsafe
 * Chapters:  09 (JVM internals), 30 §6
 * Run:       java FieldOffsets.java    (on JDK 25, add -XX:+UseCompactObjectHeaders and compare)
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JEP 519 (compact object headers); JEP 471 (Unsafe memory access deprecated)
 * Output:    deterministic for a given JDK and flags. Unsafe refuses to report a record's field offsets, so the
 *            lab says so for Point. JDK 24 and later print an Unsafe deprecation warning.
 */
import java.lang.reflect.Field;
public class FieldOffsets {
    static class Mixed { boolean flag; int count; long id; Object ref; byte b; }
    record Point(int x, int y) {}
    public static void main(String[] a) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); uf.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
        for (Class<?> c : new Class<?>[]{Mixed.class, Point.class, Long.class, Integer.class}) {
            System.out.println(c.getSimpleName() + ":");
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    System.out.println("  offset " + u.objectFieldOffset(f) + "  " + f.getType().getSimpleName() + " " + f.getName());
                } catch (UnsupportedOperationException e) {          // JDK 16+ refuses records and hidden classes
                    System.out.println("  (no offsets: " + e.getMessage() + ")");
                    break;
                }
            }
        }
    }
}
