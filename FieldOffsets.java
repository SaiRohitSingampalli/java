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
                System.out.println("  offset " + u.objectFieldOffset(f) + "  " + f.getType().getSimpleName() + " " + f.getName());
            }
        }
    }
}
