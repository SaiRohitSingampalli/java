/*
 * Lab:       A heap leak found by diffing class histograms
 * Chapters:  32 (case 5)
 * Run:       java -Xmx512m SessionLeak.java, then jcmd <pid> GC.class_histogram twice, a few seconds apart
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   the jcmd manual (GC.class_histogram)
 * Output:    runs until you stop it with Ctrl+C (left long enough, it fills the heap).
 */
import java.util.*;
public class SessionLeak {
    record Session(String id, byte[] attributes, long createdAt) {}
    static final Map<String, Session> SESSIONS = new HashMap<>();       // a "cache" with no eviction
    public static void main(String[] args) throws Exception {
        for (long i = 0; ; i++) {
            SESSIONS.put("s-" + i, new Session("s-" + i, new byte[256], System.nanoTime()));
            if (i % 2000 == 0) Thread.sleep(10);
        }
    }
}
