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
