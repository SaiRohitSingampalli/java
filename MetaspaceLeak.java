import java.lang.management.*;
import java.util.*;
public class MetaspaceLeak {
    static final List<Object> plugins = new ArrayList<>();               // e.g. "reloaded" scripts or plugins kept in a registry
    static final class PluginLoader extends ClassLoader {
        PluginLoader() { super(MetaspaceLeak.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass("Plugin", bytes, 0, bytes.length); }
    }
    public static void main(String[] args) throws Exception {
        byte[] bytes;
        try (var in = MetaspaceLeak.class.getResourceAsStream("/Plugin.class")) { bytes = in.readAllBytes(); }
        MemoryPoolMXBean metaspace = ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getName().equals("Metaspace")).findFirst().orElseThrow();
        int i = 0;
        try {
            while (true) {
                Class<?> c = new PluginLoader().define(bytes);           // a NEW loader each time = a new class in Metaspace
                plugins.add(c.getDeclaredConstructor().newInstance());   // instance -> class -> loader: nothing can be unloaded
                if (++i % 4000 == 0) System.out.printf("plugin classes loaded=%5d  metaspace used=%2d MB%n", i, metaspace.getUsage().getUsed() >> 20);
            }
        } catch (OutOfMemoryError e) {
            int loaded = i;
            plugins.clear();                                             // let the loaders (and their classes) be unloaded
            System.gc();
            System.out.println("after " + loaded + " plugin classes: " + e);
        }
    }
}
