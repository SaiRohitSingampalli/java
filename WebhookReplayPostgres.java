/*
 * Lab:       Webhook replays against a real PostgreSQL: why "one delivery per (event, endpoint)" refuses a replay, a
 *            replay ID that makes each replay a delivery of its own, and an Idempotency-Key on the replay call
 * Chapters:  38 (webhook delivery case study) §3.1, §3.2 and §7
 * Run:       java -cp /usr/share/java/postgresql.jar WebhookReplayPostgres.java "jdbc:postgresql://localhost:5433/payments_lab?user=lab&password=lab"
 * Needs:     JDK 21 or newer; the PostgreSQL JDBC driver (tested: 42.7.2); a THROWAWAY PostgreSQL 14 or newer
 *            (tested: 16.15). It drops and recreates the schema webhooks_lab, and refuses a database whose name
 *            doesn't end in _lab. The database PaymentsPostgres uses will do.
 * Verified:  JDK 21.0.12 and 25.0.5 with PostgreSQL 16.15 on Linux x86-64, 2026-11-21
 * Sources:   Standard Webhooks specification (webhook-id): https://github.com/standard-webhooks/standard-webhooks
 *            PostgreSQL 16 documentation, INSERT ... ON CONFLICT: https://www.postgresql.org/docs/16/sql-insert.html
 * Output:    deterministic. Exits with status 1 if a check fails.
 */
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class WebhookReplayPostgres {

    static final long TENANT = 42, ENDPOINT = 7;
    static final UUID EVENT = UUID.nameUUIDFromBytes("order.shipped:ord_1001".getBytes(StandardCharsets.UTF_8));
    static final List<String> failures = new CopyOnWriteArrayList<>();
    static BlockingQueue<Connection> pool;

    static String deliveryTable(String uniqueOn) {
        return """
            DROP TABLE IF EXISTS delivery;
            CREATE TABLE delivery (
                id          bigserial PRIMARY KEY,
                event_id    uuid      NOT NULL,
                endpoint_id bigint    NOT NULL,
                replay_id   bigint    NOT NULL DEFAULT 0,      -- 0: the original, written by fan-out; else replay_request.id
                webhook_id  uuid      NOT NULL UNIQUE,         -- what receivers dedupe on; the same for every attempt
                state       text      NOT NULL DEFAULT 'PENDING',
                UNIQUE %s
            );""".formatted(uniqueOn);
    }

    static final String REPLAY_TABLE = """
        CREATE TABLE replay_request (
            id              bigserial   PRIMARY KEY,           -- the replay ID
            tenant_id       bigint      NOT NULL,
            idempotency_key text,                              -- from the tenant's call; NULL never conflicts
            request_hash    text        NOT NULL,
            event_id        uuid        NOT NULL,
            endpoint_id     bigint      NOT NULL,
            created_at      timestamptz NOT NULL DEFAULT now(),
            UNIQUE (tenant_id, idempotency_key)
        );""";

    /** The original's ID is the one producers already derive in stage 1; a replay adds its own ID to the name. */
    static UUID webhookId(UUID event, long endpoint, long replayId) {
        String name = replayId == 0 ? event + "|" + endpoint : event + "|" + endpoint + "|" + replayId;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    static UUID stageOneId(UUID event, long endpoint) {                  // what the producers' sends carry (§7, stage 1)
        return UUID.nameUUIDFromBytes((event + "|" + endpoint).getBytes(StandardCharsets.UTF_8));
    }

    record Response(int status, String body) {
        @Override public String toString() { return status + " " + body; }
    }

    /** Fan-out: one delivery per event and endpoint, so an event processed twice doesn't send twice. */
    static boolean fanOut(UUID event, long endpoint) throws SQLException {
        return withConnection(c -> exec(c, "INSERT INTO delivery (event_id, endpoint_id, replay_id, webhook_id) VALUES (?, ?, 0, ?) ON CONFLICT DO NOTHING",
                                        event, endpoint, webhookId(event, endpoint, 0)) == 1);
    }

    /** POST /replays: the request row and its delivery commit together, so a retry finds both or neither. */
    static Response replay(String idempotencyKey, UUID event, long endpoint) throws SQLException {
        String hash = event + "|" + endpoint;
        return inTransaction(c -> {
            String id = queryString(c, """
                INSERT INTO replay_request (tenant_id, idempotency_key, request_hash, event_id, endpoint_id)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING RETURNING id""", TENANT, idempotencyKey, hash, event, endpoint);
            if (id == null) {                                               // a retry: answer as the first call did
                String earlier = queryString(c, "SELECT id || ' ' || request_hash FROM replay_request WHERE tenant_id = ? AND idempotency_key = ?",
                                             TENANT, idempotencyKey);
                String[] parts = earlier.split(" ", 2);
                return parts[1].equals(hash) ? new Response(202, "replay " + parts[0]) : new Response(422, "idempotency_key_reused");
            }
            long replayId = Long.parseLong(id);
            int queued = exec(c, "INSERT INTO delivery (event_id, endpoint_id, replay_id, webhook_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                              event, endpoint, replayId, webhookId(event, endpoint, replayId));
            return new Response(202, "replay " + replayId + (queued == 1 ? "" : " (nothing queued)"));
        });
    }

    public static void main(String[] args) throws Exception {
        String url = args.length > 0 ? args[0] : System.getenv("PG_URL");
        if (url == null) {
            System.err.println("usage: java -cp postgresql.jar WebhookReplayPostgres.java \"jdbc:postgresql://host:port/some_lab?user=...&password=...\"");
            System.exit(2);
        }
        try (Connection c = DriverManager.getConnection(url)) {
            String db = queryString(c, "SELECT current_database()");
            if (!db.endsWith("_lab")) {
                System.err.println("refusing to touch database " + db + ": use a throwaway database whose name ends in _lab");
                System.exit(1);
            }
            c.createStatement().execute("DROP SCHEMA IF EXISTS webhooks_lab CASCADE; CREATE SCHEMA webhooks_lab; SET search_path = webhooks_lab;"
                                        + REPLAY_TABLE + deliveryTable("(event_id, endpoint_id, replay_id)"));
            System.out.printf("PostgreSQL %s, database %s: schema webhooks_lab recreated%n%n", queryString(c, "SHOW server_version").split(" ")[0], db);
        }
        pool = new ArrayBlockingQueue<>(16);
        for (int i = 0; i < 16; i++) {
            Connection c = DriverManager.getConnection(url);
            c.createStatement().execute("SET search_path = webhooks_lab");
            pool.add(c);
        }
        try {
            part1();
            part2();
            part3();
            part4();
        } finally {
            for (Connection c : pool) c.close();
        }
        System.out.println();
        System.out.println(failures.isEmpty() ? "All checks passed." : "CHECKS FAILED:\n  " + String.join("\n  ", failures));
        if (!failures.isEmpty()) System.exit(1);
    }

    static void part1() throws Exception {
        System.out.println("1. A tenant replays one delivered event twice, a day apart (each replay inserts a delivery, as fan-out does)");
        System.out.printf("   %-40s %16s %29s %21s%n", "delivery rows unique on", "replays accepted", "deliveries queued by replays", "distinct webhook-ids");
        for (String uniqueOn : List.of("(event_id, endpoint_id)", "(event_id, endpoint_id, replay_id)")) {
            withConnection(c -> c.createStatement().execute(deliveryTable(uniqueOn)));
            reset();
            fanOut(EVENT, ENDPOINT);
            int accepted = 0;
            for (String key : List.of("replay-monday", "replay-tuesday"))
                if (replay(key, EVENT, ENDPOINT).status() == 202) accepted++;
            long byReplays = count("SELECT count(*) FROM delivery WHERE replay_id > 0");
            long ids = count("SELECT count(DISTINCT webhook_id) FROM delivery");
            System.out.printf("   %-40s %,16d %,29d %,21d%n", uniqueOn, accepted, byReplays, ids);
            if (uniqueOn.contains("replay_id"))
                expect("part 1: each replay queued a delivery with a webhook-id of its own", byReplays == 2 && ids == 3);
            else
                silentlyDropped = accepted == 2 && byReplays == 0;
        }
        if (silentlyDropped)
            System.out.println("   With the first constraint, both replays were accepted and nothing was sent: ON CONFLICT DO NOTHING hid it.");
        expect("part 1: the old constraint swallows replays (the bug this lab reproduces)", silentlyDropped);
    }

    static boolean silentlyDropped;

    static void part2() throws Exception {
        System.out.println();
        reset();
        boolean first = fanOut(EVENT, ENDPOINT), second = fanOut(EVENT, ENDPOINT);
        long originals = count("SELECT count(*) FROM delivery WHERE replay_id = 0");
        String id = withConnection(c -> queryString(c, "SELECT webhook_id FROM delivery WHERE replay_id = 0"));
        boolean sameAsStageOne = id.equals(stageOneId(EVENT, ENDPOINT).toString());
        System.out.printf("2. Fan-out processes the same event twice (a redelivered Kafka message): rows inserted %d, then %d; originals: %d%n",
                          first ? 1 : 0, second ? 1 : 0, originals);
        System.out.println("   its webhook-id equals the one producers derived in stage 1 from (event_id, endpoint_id): " + (sameAsStageOne ? "yes" : "NO"));
        expect("part 2: the fan-out still dedupes on (event, endpoint) for the original", first && !second && originals == 1);
        expect("part 2: the original keeps the stage 1 webhook-id", sameAsStageOne);
    }

    static void part3() throws Exception {
        System.out.println();
        System.out.println("3. The portal's replay call is retried: 3 times in a row, then 8 copies at once");
        System.out.printf("   %-44s %15s %11s   %s%n", "replay call", "replay requests", "deliveries", "responses");
        for (boolean withKey : new boolean[] {false, true}) {
            reset();
            fanOut(EVENT, ENDPOINT);
            String key = withKey ? "portal-7f3a" : null;
            List<Response> responses = new ArrayList<>();
            for (int i = 0; i < 3; i++) responses.add(replay(key, EVENT, ENDPOINT));
            responses.addAll(together(8, () -> replay(key, EVENT, ENDPOINT)));
            long requests = count("SELECT count(*) FROM replay_request");
            long deliveries = count("SELECT count(*) FROM delivery WHERE replay_id > 0");
            long distinct = responses.stream().map(Response::toString).distinct().count();
            String summary = distinct == 1 ? responses.size() + " x " + responses.get(0)
                                           : responses.size() + " x 202, " + distinct + " different replay IDs";
            System.out.printf("   %-44s %,15d %,11d   %s%n", withKey ? "Idempotency-Key, UNIQUE (tenant_id, key)" : "no Idempotency-Key",
                              requests, deliveries, summary);
            if (withKey) {
                expect("part 3: 11 calls with one key made one replay", requests == 1 && deliveries == 1 && distinct == 1);
                Response misuse = replay(key, UUID.nameUUIDFromBytes("another event".getBytes(StandardCharsets.UTF_8)), ENDPOINT);
                System.out.printf("   %-44s %,15d %,11d   %s%n", "the same key with a different event",
                                  count("SELECT count(*) FROM replay_request"), count("SELECT count(*) FROM delivery WHERE replay_id > 0"), misuse);
                expect("part 3: reusing a key for another event was refused", misuse.status() == 422);
            }
        }
    }

    static void part4() throws Exception {
        System.out.println();
        reset();
        fanOut(EVENT, ENDPOINT);
        AtomicInteger key = new AtomicInteger();
        List<Response> responses = together(20, () -> replay("support-" + key.incrementAndGet(), EVENT, ENDPOINT));
        long deliveries = count("SELECT count(*) FROM delivery WHERE replay_id > 0");
        long ids = count("SELECT count(DISTINCT webhook_id) FROM delivery WHERE replay_id > 0");
        long errors = responses.stream().filter(r -> r.status() != 202 || r.body().contains("nothing")).count();
        System.out.printf("4. 20 different replay requests for the same event at once: %d deliveries, %d distinct webhook-ids, %d errors%n",
                          deliveries, ids, errors);
        expect("part 4: concurrent replays each got a delivery and an ID", deliveries == 20 && ids == 20 && errors == 0);
    }

    // ------------------------------------------------------------------ helpers

    interface SqlWork<T> { T run(Connection c) throws SQLException; }
    interface Call { Response call() throws Exception; }

    static void reset() throws SQLException {
        withConnection(c -> exec(c, "TRUNCATE delivery, replay_request RESTART IDENTITY"));
    }

    static long count(String sql) throws SQLException {
        return withConnection(c -> Long.parseLong(queryString(c, sql)));
    }

    static List<Response> together(int copies, Call call) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try (ExecutorService threads = Executors.newFixedThreadPool(copies)) {
            for (int i = 0; i < copies; i++) futures.add(threads.submit(() -> { start.await(); return call.call(); }));
            start.countDown();
        }
        List<Response> responses = new ArrayList<>();
        for (Future<Response> f : futures) responses.add(f.get());
        return responses;
    }

    static <T> T withConnection(SqlWork<T> work) throws SQLException {
        Connection c;
        try { c = pool.take(); } catch (InterruptedException e) { throw new SQLException(e); }
        try { return work.run(c); } finally { pool.add(c); }
    }

    static <T> T inTransaction(SqlWork<T> work) throws SQLException {
        return withConnection(c -> {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        });
    }

    static int exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps.executeUpdate();
        }
    }

    static String queryString(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        }
    }

    static void expect(String what, boolean ok) {
        if (!ok) failures.add(what);
    }
}
