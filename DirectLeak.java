/*
 * Lab:       OOMKilled while the heap looks healthy: a direct-buffer leak, found with Native Memory Tracking
 * Chapters:  32 (case 1)
 * Run:       javac -d /tmp/ch32 DirectLeak.java && java -Xmx256m -XX:MaxDirectMemorySize=64m -XX:NativeMemoryTracking=summary \
 *              -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics -cp /tmp/ch32 DirectLeak
 *            Compile it first: run as `java DirectLeak.java`, the launcher's in-process compile leaves about
 *            20 MB of garbage on the heap, and the heap figure no longer shows how small the heap really is.
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   Native Memory Tracking in the JDK troubleshooting guide; ByteBuffer.allocateDirect API docs
 * Output:    allocates until the 64 MB direct-memory limit, reports the OutOfMemoryError and exits 0. The buffer
 *            and heap-used lines are deterministic; the NMT figures vary a little.
 */
import java.lang.management.*;
import java.nio.ByteBuffer;
import java.util.*;
public class DirectLeak {
    static final List<ByteBuffer> retained = new ArrayList<>();          // e.g. a buffer pool that never returns buffers
    public static void main(String[] args) {
        BufferPoolMXBean direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(b -> b.getName().equals("direct")).findFirst().orElseThrow();
        try {
            for (int i = 1; ; i++) {
                retained.add(ByteBuffer.allocateDirect(1 << 20));       // 1 MB off-heap each
                if (i % 16 == 0)
                    System.out.printf("buffers=%3d  heap used=%3d MB  direct=%3d MB%n", i,
                            ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed() >> 20, direct.getMemoryUsed() >> 20);
            }
        } catch (OutOfMemoryError e) {
            System.out.println(e);
        }
    }
}
