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
