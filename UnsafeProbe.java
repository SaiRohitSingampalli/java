/*
 * Lab:       What libraries that use sun.misc.Unsafe do when the JDK warns, and when it refuses
 * Chapters:  37 (fleet JDK upgrade), 14 §9 (integrity by default)
 * Run:       java -cp "$GUAVA_JAR:$PROTOBUF_JAR" UnsafeProbe.java
 *            java --sun-misc-unsafe-memory-access=deny -cp "$GUAVA_JAR:$PROTOBUF_JAR" UnsafeProbe.java
 * Needs:     JDK 17+ (the flag needs JDK 23+); Guava and protobuf-java JARs (tested: Guava 32.0.1, protobuf-java 3.21.12)
 * Verified:  JDK 17.0.21, 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   JEP 471 https://openjdk.org/jeps/471 · JEP 498 https://openjdk.org/jeps/498
 * Output:    deterministic. JDK 24+ prints a one-time warning naming the first caller. Under "deny", each
 *            library either falls back to pure Java or fails; the probe reports both and always exits 0.
 */
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

public class UnsafeProbe {

    public static void main(String[] args) {
        String mode = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("--sun-misc-unsafe-memory-access")).findFirst().orElse("(JDK default)");
        System.out.printf("JDK %d, sun.misc.Unsafe memory access: %s%n", Runtime.version().feature(), mode);
        probe("Guava UnsignedBytes comparator", () -> {
            var comparator = com.google.common.primitives.UnsignedBytes.lexicographicalComparator();
            comparator.compare(new byte[] {1, 2}, new byte[] {1, 3});
            return comparator.toString();
        });
        probe("Guava murmur3_128 hash", () ->
                com.google.common.hash.Hashing.murmur3_128().hashString("fleet", StandardCharsets.UTF_8).toString());
        probe("protobuf Struct round trip", () -> {
            var message = com.google.protobuf.Struct.newBuilder()
                    .putFields("service", com.google.protobuf.Value.newBuilder().setStringValue("orders").build()).build();
            return "service=" + com.google.protobuf.Struct.parseFrom(message.toByteArray()).getFieldsOrThrow("service").getStringValue();
        });
    }

    static void probe(String name, Callable<String> work) {
        try {
            System.out.printf("  %-32s ok: %s%n", name, work.call());
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) root = root.getCause();
            System.out.printf("  %-32s FAILS: %s, caused by %s: %s%n", name, t.getClass().getSimpleName(),
                              root.getClass().getSimpleName(), root.getMessage());
        }
    }
}
