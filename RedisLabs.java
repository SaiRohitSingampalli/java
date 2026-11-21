/*
 * Lab:       Redis experiments: pipelining, KEYS vs SCAN, DEL vs UNLINK, lost updates, eviction, lock leases, streams
 * Chapters:  34 (data stores)
 * Run:       redis-server --port 6380 --save '' --appendonly no --daemonize yes, then java RedisLabs.java all localhost 6380
 * Needs:     JDK 21 or newer; a throwaway Redis 7+ or Valkey server (tested: Redis 7.0.15). No client library
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   Redis documentation: pipelining, SCAN, UNLINK, eviction policies, distributed locks, streams
 * Output:    timings vary. Exits with status 1 if the server is unreachable or already holds data.
 */
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Chapter 34 labs: Redis behaviour you can measure, with a small RESP client and no libraries.
 *
 *   java RedisLabs.java <experiment> [host] [port]
 *   experiments: pipeline | keys | unlink | counter | eviction | lock | streams | all
 *
 * The labs FLUSHALL and change maxmemory, so they refuse to run against a server that holds data.
 * Start a throwaway one:  redis-server --port 6380 --save '' --appendonly no
 */
public class RedisLabs {
    static String host = "localhost";
    static int port = 6379;

    public static void main(String[] args) throws Exception {
        String what = args.length > 0 ? args[0] : "all";
        if (args.length > 1) host = args[1];
        if (args.length > 2) port = Integer.parseInt(args[2]);
        try (Redis r = connect()) {
            long keys = (Long) r.call("DBSIZE");
            if (keys != 0) {
                System.err.println("Refusing to run: this server holds " + keys + " keys, and the labs FLUSHALL.");
                System.err.println("Start a throwaway server:  redis-server --port 6380 --save '' --appendonly no");
                System.exit(1);
            }
            System.out.println("Redis " + info(r, "server", "redis_version") + " at " + host + ":" + port);
            for (int i = 0; i < 3_000; i++) r.call("SET", "warmup", "v");       // warm up the JIT before timing anything
            r.call("FLUSHALL");
        }
        switch (what) {
            case "pipeline" -> pipeline();
            case "keys" -> keysVsScan();
            case "unlink" -> delVsUnlink();
            case "counter" -> counter();
            case "eviction" -> eviction();
            case "lock" -> lock();
            case "streams" -> streams();
            case "all" -> { pipeline(); keysVsScan(); delVsUnlink(); counter(); eviction(); lock(); streams(); }
            default -> System.err.println("unknown experiment: " + what);
        }
    }

    // ---------------------------------------------------------------- 1. round trips
    static void pipeline() throws Exception {
        header("1. Round trips vs pipelining");
        int n = 10_000;
        try (Redis r = connect()) {
            time("localhost, one command per round trip", n, () -> { for (int i = 0; i < n; i++) r.call("SET", "k" + i, "v"); });
            time("localhost, pipelined (batches of 1,000)", n, () -> pipelinedSets(r, n, 1_000));
        }
        try (DelayProxy proxy = new DelayProxy(host, port, 250);                // 0.25 ms each way = 0.5 ms round trip
             Redis r = new Redis("localhost", proxy.port())) {
            for (int i = 0; i < 200; i++) r.call("PING");
            long t0 = System.nanoTime();
            for (int i = 0; i < 500; i++) r.call("PING");
            String rtt = String.format("%.1f ms RTT", ms(t0) / 500);         // the proxy's own thread hops add a little
            System.out.println("  (through the delay proxy, a PING round trip now takes " + rtt.replace(" RTT", "") + ")");
            int m = 1_000;                                                       // fewer: every round trip is now slow
            time(rtt + ", one command per round trip", m, () -> { for (int i = 0; i < m; i++) r.call("SET", "k" + i, "v"); });
            time(rtt + ", pipelined (batches of 1,000)", n, () -> pipelinedSets(r, n, 1_000));
            time(rtt + ", MSET with 1,000 keys each", n, () -> {
                for (int i = 0; i < n; i += 1_000) {
                    String[] a = new String[1 + 2 * 1_000];
                    a[0] = "MSET";
                    for (int j = 0; j < 1_000; j++) { a[1 + 2 * j] = "k" + (i + j); a[2 + 2 * j] = "v"; }
                    r.call(a);
                }
            });
        }
        flushAll();
    }

    static void pipelinedSets(Redis r, int n, int batch) throws IOException {
        for (int i = 0; i < n; i += batch) {
            int end = Math.min(n, i + batch);
            for (int j = i; j < end; j++) r.send("SET", "k" + j, "v");
            r.flush();                                                          // one write for the whole batch…
            for (int j = i; j < end; j++) r.read();                             // …then all the replies
        }
    }

    // ---------------------------------------------------------------- 2. KEYS vs SCAN
    static void keysVsScan() throws Exception {
        header("2. KEYS blocks every client; SCAN doesn't");
        int n = 1_000_000;
        try (Redis r = connect()) {
            load(r, n);
            System.out.printf("  loaded %,d keys%n", n);
            try (Probe probe = new Probe()) {
                Thread.sleep(200);
                probe.reset();
                long t0 = System.nanoTime();
                int returned = ((List<?>) r.call("KEYS", "*")).size();
                System.out.printf("  KEYS *   returned %,d keys in %,6.0f ms   | other client: %s%n", returned, ms(t0), probe.reset());
                t0 = System.nanoTime();
                long visited = 0; int calls = 0; String cursor = "0";
                do {
                    List<?> reply = (List<?>) r.call("SCAN", cursor, "COUNT", "1000");
                    cursor = (String) reply.get(0);
                    visited += ((List<?>) reply.get(1)).size();
                    calls++;
                } while (!cursor.equals("0"));
                System.out.printf("  SCAN     visited %,d keys in %,6.0f ms (%,d calls) | other client: %s%n", visited, ms(t0), calls, probe.reset());
            }
        }
        flushAll();
    }

    // ---------------------------------------------------------------- 3. DEL vs UNLINK
    static void delVsUnlink() throws Exception {
        header("3. Deleting one big key: DEL vs UNLINK");
        try (Redis r = connect(); Probe probe = new Probe()) {
            for (String command : List.of("DEL", "UNLINK")) {
                bigHash(r, "big", 2_000_000);
                String before = info(r, "memory", "used_memory_human");
                Thread.sleep(200);
                probe.reset();
                long t0 = System.nanoTime();
                r.call(command, "big");
                double tookMs = ms(t0);
                String pending = info(r, "memory", "lazyfree_pending_objects");
                Thread.sleep(300);
                System.out.printf("  %-6s a 2,000,000-field hash (%s): the command took %7.2f ms, lazyfree_pending_objects=%s | other client: %s%n",
                        command, before, tookMs, pending, probe.reset());
            }
        }
        flushAll();
    }

    // ---------------------------------------------------------------- 4. read-modify-write
    interface Step { int run(Redis r) throws IOException; }                        // returns retries

    static void counter() throws Exception {
        header("4. Read-modify-write: 4 clients x 2,500 increments each");
        incrementWith("GET, then SET", r -> {
            long v = number(r.call("GET", "counter"));
            r.call("SET", "counter", String.valueOf(v + 1));
            return 0;
        });
        incrementWith("WATCH + MULTI/EXEC, retry on abort", r -> {
            for (int retries = 0; ; retries++) {
                r.call("WATCH", "counter");
                long v = number(r.call("GET", "counter"));
                r.send("MULTI"); r.send("SET", "counter", String.valueOf(v + 1)); r.send("EXEC"); r.flush();
                r.read(); r.read();                                              // +OK, +QUEUED
                if (r.read() != null) return retries;                           // null reply = someone changed it: retry
            }
        });
        incrementWith("INCR", r -> { r.call("INCR", "counter"); return 0; });
        incrementWith("Lua script (atomic on the server)", r -> {
            r.call("EVAL", "local v = tonumber(redis.call('GET', KEYS[1]) or '0') "
                         + "redis.call('SET', KEYS[1], v + 1) return v + 1", "1", "counter");
            return 0;
        });
        flushAll();
    }

    static void incrementWith(String label, Step step) throws Exception {
        int clients = 4, each = 2_500;
        try (Redis r = connect()) { r.call("DEL", "counter"); }
        AtomicLong retries = new AtomicLong();
        long t0 = System.nanoTime();
        try (ExecutorService pool = Executors.newFixedThreadPool(clients)) {
            for (int c = 0; c < clients; c++) pool.submit(() -> {
                try (Redis r = connect()) { for (int i = 0; i < each; i++) retries.addAndGet(step.run(r)); }
                return null;
            });
        }
        try (Redis r = connect()) {
            long value = number(r.call("GET", "counter"));
            System.out.printf("  %-36s counter=%,6d of %,d  (%s)  retries=%,d  %,.0f ms%n", label, value, clients * each,
                    value == clients * each ? "correct" : String.format("lost %,d", clients * each - value), retries.get(), ms(t0));
        }
    }

    // ---------------------------------------------------------------- 5. eviction policies
    static void eviction() throws Exception {
        header("5. maxmemory 32mb, writing 60,000 values of 1 KB (no TTLs)");
        String value = "x".repeat(1_024);
        try (Redis r = connect()) {
            for (String policy : List.of("noeviction", "volatile-lru", "allkeys-lru")) {
                r.call("FLUSHALL");
                r.call("CONFIG", "SET", "maxmemory", "32mb");
                r.call("CONFIG", "SET", "maxmemory-policy", policy);
                r.call("CONFIG", "RESETSTAT");
                int written = 0; String error = null;
                for (int i = 0; i < 60_000; i++) {
                    if (r.call("SET", "key:" + i, value) instanceof RedisError e) { error = e.message(); break; }
                    written++;
                }
                System.out.printf("  %-13s wrote %,6d  evicted %,6d  key:0 still there: %-5s  %s%n", policy, written,
                        Long.parseLong(info(r, "stats", "evicted_keys")), r.call("EXISTS", "key:0").equals(1L),
                        error == null ? "no errors" : "then: " + error);
            }
            r.call("CONFIG", "SET", "maxmemory", "0");
            r.call("CONFIG", "SET", "maxmemory-policy", "noeviction");
        }
        flushAll();
    }

    // ---------------------------------------------------------------- 6. lease-based locks
    static void lock() throws Exception {
        header("6. A 200 ms lock lease, and a client that pauses for 300 ms");
        String release = "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end";
        String fencedWrite = "local highest = tonumber(redis.call('GET', KEYS[1]) or '0') "
                + "if tonumber(ARGV[1]) < highest then return 0 end "
                + "redis.call('SET', KEYS[1], ARGV[1]) redis.call('SET', KEYS[2], ARGV[2]) return 1";
        try (Redis a = connect(); Redis b = connect()) {
            String tokenA = "A-" + UUID.randomUUID(), tokenB = "B-" + UUID.randomUUID();
            Object gotA = a.call("SET", "lock:report", tokenA, "NX", "PX", "200");
            long fenceA = (Long) a.call("INCR", "fence:report");
            System.out.println("  A: SET lock NX PX 200 -> " + gotA + ", fencing token " + fenceA);
            Thread.sleep(300);                                                   // a GC pause, a slow call, a stalled VM
            Object gotB = b.call("SET", "lock:report", tokenB, "NX", "PX", "5000");
            long fenceB = (Long) b.call("INCR", "fence:report");
            System.out.println("  B: SET lock NX PX 5000 -> " + gotB + ", fencing token " + fenceB + "   (A's lease expired while A was paused)");
            Object naive = a.call("DEL", "lock:report");
            System.out.println("  A wakes up, finishes, and releases with plain DEL -> deleted " + naive + " key: B's lock is gone (EXISTS=" + a.call("EXISTS", "lock:report") + ")");
            b.call("SET", "lock:report", tokenB, "PX", "5000");
            Object safe = a.call("EVAL", release, "1", "lock:report", tokenA);
            System.out.println("  Same release as compare-and-delete (Lua) -> deleted " + safe + ": B's lock survives (EXISTS=" + a.call("EXISTS", "lock:report") + ")");
            b.call("EVAL", fencedWrite, "2", "report:fence", "report:data", String.valueOf(fenceB), "written by B");
            Object stale = a.call("EVAL", fencedWrite, "2", "report:fence", "report:data", String.valueOf(fenceA), "written by A");
            System.out.println("  Storage that checks fencing tokens: A's write with token " + fenceA + " -> " + (stale.equals(0L) ? "rejected" : "accepted")
                    + "; data = '" + a.call("GET", "report:data") + "'");
        }
        flushAll();
    }

    // ---------------------------------------------------------------- 7. streams vs pub/sub
    static void streams() throws Exception {
        header("7. Streams: a consumer dies before XACK; another one reclaims its messages");
        try (Redis r = connect()) {
            for (int i = 1; i <= 6; i++) r.call("XADD", "orders", "*", "orderId", "A-" + i);
            r.call("XGROUP", "CREATE", "orders", "billing", "0");
            List<?> c1 = (List<?>) ((List<?>) ((List<?>) r.call("XREADGROUP", "GROUP", "billing", "c1", "COUNT", "3", "STREAMS", "orders", ">")).get(0)).get(1);
            System.out.println("  c1 read " + c1.size() + " messages and crashed before XACK");
            List<?> c2 = (List<?>) ((List<?>) ((List<?>) r.call("XREADGROUP", "GROUP", "billing", "c2", "STREAMS", "orders", ">")).get(0)).get(1);
            for (Object m : c2) r.call("XACK", "orders", "billing", (String) ((List<?>) m).get(0));
            System.out.println("  c2 read and acknowledged the other " + c2.size());
            System.out.println("  XPENDING summary: " + r.call("XPENDING", "orders", "billing"));
            Thread.sleep(150);
            List<?> claimed = (List<?>) ((List<?>) r.call("XAUTOCLAIM", "orders", "billing", "c2", "100", "0-0", "COUNT", "10")).get(1);
            System.out.println("  c2: XAUTOCLAIM min-idle 100 ms -> claimed " + claimed.size() + " messages");
            for (Object p : (List<?>) r.call("XPENDING", "orders", "billing", "-", "+", "10")) {
                List<?> e = (List<?>) p;
                System.out.println("    pending " + e.get(0) + " owner=" + e.get(1) + " delivered " + e.get(3) + " times");
                r.call("XACK", "orders", "billing", (String) e.get(0));
            }
            System.out.println("  after XACK, pending = " + ((List<?>) r.call("XPENDING", "orders", "billing")).get(0));
            System.out.println("  PUBLISH to a channel with nobody subscribed -> received by " + r.call("PUBLISH", "order-events", "A-7") + " clients; the message is gone");
        }
        flushAll();
    }

    // ---------------------------------------------------------------- helpers
    static Redis connect() throws IOException { return new Redis(host, port); }

    static void flushAll() throws IOException { try (Redis r = connect()) { r.call("FLUSHALL"); } }

    static void load(Redis r, int n) throws IOException {
        for (int i = 0; i < n; i += 10_000) {
            int end = Math.min(n, i + 10_000);
            for (int j = i; j < end; j++) r.send("SET", "key:" + j, "v");
            r.flush();
            for (int j = i; j < end; j++) r.read();
        }
    }

    static void bigHash(Redis r, String key, int fields) throws IOException {
        int perCommand = 1_000, pending = 0;
        for (int start = 0; start < fields; start += perCommand) {
            String[] a = new String[2 + 2 * perCommand];
            a[0] = "HSET"; a[1] = key;
            for (int i = 0; i < perCommand; i++) { a[2 + 2 * i] = "f" + (start + i); a[3 + 2 * i] = "v"; }
            r.send(a);
            if (++pending == 100) { r.flush(); while (pending > 0) { r.read(); pending--; } }
        }
        r.flush();
        while (pending-- > 0) r.read();
    }

    static String info(Redis r, String section, String field) throws IOException {
        for (String line : ((String) r.call("INFO", section)).split("\r\n"))
            if (line.startsWith(field + ":")) return line.substring(field.length() + 1);
        return "?";
    }

    static long number(Object reply) { return reply == null ? 0 : Long.parseLong((String) reply); }

    static double ms(long t0) { return (System.nanoTime() - t0) / 1e6; }

    static void header(String title) { System.out.println("\n" + title); }

    interface Body { void run() throws Exception; }

    /** Runs the body once to warm up, then three times, and reports the median. */
    static void time(String label, int ops, Body body) throws Exception {
        body.run();
        double[] runs = new double[3];
        for (int i = 0; i < runs.length; i++) {
            long t0 = System.nanoTime();
            body.run();
            runs[i] = ms(t0);
        }
        Arrays.sort(runs);
        double took = runs[1];
        System.out.printf("  %-42s %,6d ops in %,8.1f ms = %,10.0f ops/s%n", label, ops, took, ops / (took / 1_000));
    }

    record RedisError(String message) {}

    /** Just enough RESP2: arrays of bulk strings out; simple strings, errors, integers, bulk strings and arrays in. */
    static final class Redis implements AutoCloseable {
        private static final byte[] CRLF = {'\r', '\n'};
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Redis(String host, int port) throws IOException {
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            in = new BufferedInputStream(socket.getInputStream(), 1 << 16);
            out = new BufferedOutputStream(socket.getOutputStream(), 1 << 16);
        }

        void send(String... args) throws IOException {
            ascii("*" + args.length + "\r\n");
            for (String a : args) {
                byte[] b = a.getBytes(StandardCharsets.UTF_8);
                ascii("$" + b.length + "\r\n");
                out.write(b);
                out.write(CRLF);
            }
        }

        void flush() throws IOException { out.flush(); }

        Object call(String... args) throws IOException { send(args); flush(); return read(); }

        Object read() throws IOException {
            int type = in.read();
            if (type < 0) throw new EOFException("connection closed");
            String line = line();
            return switch (type) {
                case '+' -> line;
                case '-' -> new RedisError(line);
                case ':' -> Long.parseLong(line);
                case '$' -> {
                    int n = Integer.parseInt(line);
                    if (n < 0) yield null;
                    byte[] b = in.readNBytes(n);
                    line();
                    yield new String(b, StandardCharsets.UTF_8);
                }
                case '*' -> {
                    int n = Integer.parseInt(line);
                    if (n < 0) yield null;
                    List<Object> items = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) items.add(read());
                    yield items;
                }
                default -> throw new IOException("unexpected RESP type '" + (char) type + "'");
            };
        }

        private String line() throws IOException {
            StringBuilder sb = new StringBuilder();
            for (int c; (c = in.read()) != '\r'; ) {
                if (c < 0) throw new EOFException("connection closed");
                sb.append((char) c);
            }
            in.read();                                                           // '\n'
            return sb.toString();
        }

        private void ascii(String s) throws IOException { out.write(s.getBytes(StandardCharsets.US_ASCII)); }

        @Override public void close() throws IOException { socket.close(); }
    }

    /** A second client that sends one PING per millisecond and records how long each one waited. */
    static final class Probe implements AutoCloseable {
        private final Redis redis = connect();
        private final AtomicLong worst = new AtomicLong(), slow = new AtomicLong(), count = new AtomicLong();
        private volatile boolean running = true;
        private final Thread thread = Thread.ofPlatform().daemon().start(this::loop);

        Probe() throws IOException {}

        private void loop() {
            while (running) {
                long t0 = System.nanoTime();
                try { redis.call("PING"); } catch (IOException e) { return; }
                long took = System.nanoTime() - t0;
                count.incrementAndGet();
                worst.accumulateAndGet(took, Math::max);
                if (took > 10_000_000) slow.incrementAndGet();
                LockSupport.parkNanos(1_000_000);
            }
        }

        String reset() {
            String s = String.format("worst PING %,.1f ms, %,d of %,d PINGs over 10 ms",
                    worst.get() / 1e6, slow.get(), count.get());
            worst.set(0); slow.set(0); count.set(0);
            return s;
        }

        @Override public void close() throws Exception { running = false; thread.join(1_000); redis.close(); }
    }

    /** Forwards bytes both ways and holds each chunk for a fixed delay: a stand-in for network latency. */
    static final class DelayProxy implements AutoCloseable {
        private record Chunk(long due, byte[] bytes) {}
        private final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final long delayNanos;

        DelayProxy(String targetHost, int targetPort, long oneWayMicros) throws IOException {
            delayNanos = oneWayMicros * 1_000;
            Thread.ofPlatform().daemon().start(() -> {
                try {
                    while (true) {
                        Socket client = server.accept(), upstream = new Socket(targetHost, targetPort);
                        client.setTcpNoDelay(true); upstream.setTcpNoDelay(true);
                        sockets.add(client); sockets.add(upstream);
                        pump(client, upstream);
                        pump(upstream, client);
                    }
                } catch (IOException closed) { /* proxy closed */ }
            });
        }

        int port() { return server.getLocalPort(); }

        private void pump(Socket from, Socket to) {
            BlockingQueue<Chunk> queue = new LinkedBlockingQueue<>();
            Thread.ofPlatform().daemon().start(() -> {                           // reader: stamp each chunk as it arrives
                byte[] buf = new byte[1 << 16];
                try {
                    InputStream in = from.getInputStream();
                    for (int n; (n = in.read(buf)) > 0; ) queue.put(new Chunk(System.nanoTime() + delayNanos, Arrays.copyOf(buf, n)));
                } catch (IOException | InterruptedException ignored) { }
                queue.offer(new Chunk(0, null));
            });
            Thread.ofPlatform().daemon().start(() -> {                           // writer: release it once the delay has passed
                try {
                    OutputStream out = to.getOutputStream();
                    for (Chunk c; (c = queue.take()).bytes() != null; ) {
                        for (long wait; (wait = c.due() - System.nanoTime()) > 0; ) LockSupport.parkNanos(wait);
                        out.write(c.bytes());
                        out.flush();
                    }
                } catch (IOException | InterruptedException ignored) { }
            });
        }

        @Override public void close() throws IOException {
            server.close();
            for (Socket s : sockets) s.close();
        }
    }
}
