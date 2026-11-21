/*
 * Lab:       The payments design against a real PostgreSQL: concurrent claims, one transaction for state, ledger and
 *            outbox, guarded updates, reconcilers sharing work with SKIP LOCKED, and the cutover in both directions
 * Chapters:  36 (payments case study) §3, §6, §7 and §8; 23 §6 and §7
 * Run:       java -cp /usr/share/java/postgresql.jar PaymentsPostgres.java "jdbc:postgresql://localhost:5433/payments_lab?user=lab&password=lab"
 * Needs:     JDK 21 or newer; the PostgreSQL JDBC driver (tested: 42.7.2); a THROWAWAY PostgreSQL 14 or newer
 *            (tested: 16.15). It drops and recreates the schema payments_lab, and refuses a database whose name
 *            doesn't end in _lab. One way to get one: docker run -d -p 5433:5432 -e POSTGRES_USER=lab
 *            -e POSTGRES_PASSWORD=lab -e POSTGRES_DB=payments_lab postgres:16
 * Verified:  JDK 21.0.12 and 25.0.5 with PostgreSQL 16.15 on Linux x86-64, 2026-11-21
 * Sources:   PostgreSQL 16 documentation: INSERT ... ON CONFLICT; SELECT ... FOR UPDATE SKIP LOCKED;
 *            transaction isolation (READ COMMITTED re-checks a WHERE clause after waiting for a row lock)
 * Output:    part 1's split between 409s and replays varies with thread timing, and so do the old handler's numbers;
 *            parts 2 to 6 are deterministic. Exits with status 1 if a check fails.
 */
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Collectors;

public class PaymentsPostgres {

    static final long MERCHANT = 7;
    static final List<String> failures = new CopyOnWriteArrayList<>();
    static String url;
    static BlockingQueue<Connection> pool;

    // ------------------------------------------------------------------ schema

    static final String SCHEMA = """
        DROP SCHEMA IF EXISTS payments_lab CASCADE;
        CREATE SCHEMA payments_lab;
        SET search_path = payments_lab;
        CREATE TABLE payment_operation (
            id              uuid        PRIMARY KEY,
            merchant_id     bigint      NOT NULL,
            idempotency_key text        NOT NULL,
            request_hash    text        NOT NULL,
            amount_minor    bigint      NOT NULL CHECK (amount_minor > 0),
            currency        char(3)     NOT NULL,
            state           text        NOT NULL CHECK (state IN ('PENDING','SUCCEEDED','DECLINED','UNKNOWN','NOT_CHARGED')),
            psp             text        NOT NULL,
            psp_charge_id   text,
            response        jsonb,
            lease_until     timestamptz,
            created_at      timestamptz NOT NULL DEFAULT now(),
            updated_at      timestamptz NOT NULL DEFAULT now(),
            UNIQUE (merchant_id, idempotency_key)
        );
        CREATE TABLE ledger_entry (
            id            bigserial PRIMARY KEY,
            operation_id  uuid   NOT NULL REFERENCES payment_operation (id),
            account       text   NOT NULL,
            debit_minor   bigint NOT NULL DEFAULT 0 CHECK (debit_minor >= 0),
            credit_minor  bigint NOT NULL DEFAULT 0 CHECK (credit_minor >= 0)
        );
        CREATE TABLE outbox (
            id            bigserial PRIMARY KEY,
            operation_id  uuid NOT NULL REFERENCES payment_operation (id),
            event         text NOT NULL
        );
        CREATE TABLE legacy_idempotency (                -- the old handler's table
            merchant_id     bigint      NOT NULL,
            idempotency_key text        NOT NULL,
            request_hash    text        NOT NULL,
            amount_minor    bigint      NOT NULL,
            currency        char(3)     NOT NULL,
            response        jsonb       NOT NULL,
            psp_charge_id   text        NOT NULL,
            created_at      timestamptz NOT NULL,
            PRIMARY KEY (merchant_id, idempotency_key)
        );
        CREATE TABLE merchant_handler (                  -- which handler serves a merchant during the cutover
            merchant_id   bigint PRIMARY KEY,
            handler       text   NOT NULL CHECK (handler IN ('OLD','DRAINING','NEW')),
            in_flight     int    NOT NULL DEFAULT 0 CHECK (in_flight >= 0)
        );
        """;

    static void reset() throws SQLException {
        withConnection(c -> exec(c, "TRUNCATE payment_operation, ledger_entry, outbox, legacy_idempotency, merchant_handler RESTART IDENTITY"));
    }

    // ------------------------------------------------------------------ a payment processor that dedupes on its own key

    record Charge(String id, String reference, long amount) {}

    static final class Psp {
        final List<Charge> charges = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Charge> byKey = new ConcurrentHashMap<>();
        final AtomicInteger sequence = new AtomicInteger(4000);
        volatile long latencyMs = 2;

        Charge charge(String idempotencyKey, String reference, long amount) {
            sleepMillis(latencyMs);
            if (idempotencyKey == null) return newCharge(reference, amount);
            return byKey.computeIfAbsent(idempotencyKey, k -> newCharge(reference, amount));
        }

        private Charge newCharge(String reference, long amount) {
            Charge c = new Charge("ch_" + sequence.incrementAndGet(), reference, amount);
            charges.add(c);
            return c;
        }

        /** Counts per checkout: the old handler's reference is the client's key, the new one's is the operation ID. */
        long checkoutsChargedTwice() {
            synchronized (charges) {
                return charges.stream().collect(Collectors.groupingBy(c -> CHECKOUT_OF.getOrDefault(c.reference(), c.reference()),
                                                                      Collectors.counting()))
                        .values().stream().filter(n -> n > 1).count();
            }
        }
    }

    record Request(String customer, long amount, String currency) {
        String hash() { return customer + "|" + amount + "|" + currency; }
    }

    record Response(int status, String body) {
        @Override public String toString() { return status + " " + body; }
    }

    static Request requestFor(String key) { return new Request("cust-" + key, 2500, "EUR"); }

    static final Map<String, String> CHECKOUT_OF = new ConcurrentHashMap<>();   // the lab's accounting only

    static UUID operationId(long merchant, String key) {
        UUID id = UUID.nameUUIDFromBytes((merchant + "|" + key).getBytes(StandardCharsets.UTF_8));
        CHECKOUT_OF.putIfAbsent(id.toString(), key);
        return id;
    }

    // ------------------------------------------------------------------ the old handler: check, charge, then save

    static final class OldHandler {
        final Psp psp;
        boolean readShim;                  // the old code also checks payment_operation before charging (rollback safety)
        OldHandler(Psp psp) { this.psp = psp; }

        Response pay(String key, Request r) throws SQLException {
            String saved = withConnection(c -> queryString(c, "SELECT response->>'body' FROM legacy_idempotency WHERE merchant_id = ? AND idempotency_key = ?", MERCHANT, key));
            if (saved != null) return new Response(201, saved + " (replayed)");
            if (readShim) {
                Response owned = withConnection(c -> stored(c, key, r));              // a payment the new handler owns
                if (owned != null) return owned;
            }
            Charge charge = psp.charge(null, key, r.amount());                       // no PSP key: the old design
            try {
                withConnection(c -> exec(c, """
                    INSERT INTO legacy_idempotency (merchant_id, idempotency_key, request_hash, amount_minor, currency, response, psp_charge_id, created_at)
                    VALUES (?, ?, ?, ?, ?, jsonb_build_object('status', 201, 'body', 'charged ' || ?::text), ?, now())""",
                    MERCHANT, key, r.hash(), r.amount(), r.currency(), charge.id(), charge.id()));
                return new Response(201, "charged " + charge.id());
            } catch (SQLException e) {
                if ("23505".equals(e.getSQLState())) return new Response(500, "duplicate key value violates unique constraint");
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------ the new handler: claim first

    static final class NewHandler {
        final Psp psp;
        boolean legacyLookup;
        String conflictTarget = "";        // "" handles a conflict on any unique constraint; a target handles only its own
        NewHandler(Psp psp) { this.psp = psp; }

        Response pay(String key, Request r) throws SQLException {
            UUID id = operationId(MERCHANT, key);
            Response existing;
            try {
                existing = withConnection(c -> {
                    if (legacyLookup) adoptLegacy(c, key);                          // a payment the old handler completed
                    boolean claimed = queryString(c, """
                        INSERT INTO payment_operation (id, merchant_id, idempotency_key, request_hash, amount_minor, currency, state, psp)
                        VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 'PSP A')
                        ON CONFLICT %s DO NOTHING RETURNING id""".formatted(conflictTarget),
                        id, MERCHANT, key, r.hash(), r.amount(), r.currency()) != null;
                    return claimed ? null : stored(c, key, r);
                });
            } catch (SQLException e) {
                if (!"23505".equals(e.getSQLState())) throw e;
                return new Response(500, "duplicate key value violates unique constraint \"payment_operation_pkey\"");
            }
            if (existing != null) return existing;
            Charge charge = psp.charge(id.toString(), id.toString(), r.amount());   // no transaction open here
            return record(id, charge);
        }


        /** The guarded update, the ledger rows and the outbox row: one transaction, so all or nothing. */
        Response record(UUID id, Charge charge) throws SQLException {
            return inTransaction(c -> recordIn(c, id, charge, true) ? new Response(201, "charged " + charge.id())
                                                                     : new Response(201, "charged " + charge.id() + " (replayed)"));
        }
    }

    /** What a retry gets for a key that payment_operation already holds; null if it holds nothing. Both handlers use it. */
    static Response stored(Connection c, String key, Request r) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT state, request_hash, response->>'body' FROM payment_operation WHERE merchant_id = ? AND idempotency_key = ?")) {
            bind(ps, MERCHANT, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                if (!rs.getString(2).equals(r.hash())) return new Response(422, "idempotency_key_reused");
                return switch (rs.getString(1)) {
                    case "SUCCEEDED" -> new Response(201, rs.getString(3) + " (replayed)");
                    case "PENDING" -> new Response(409, "idempotency_key_in_use, Retry-After: 1");
                    default -> new Response(202, "processing, Retry-After: 5");
                };
            }
        }
    }

    /** Returns true if this writer recorded the outcome; false if another writer had already (guarded only). */
    static boolean recordIn(Connection c, UUID id, Charge charge, boolean guarded) throws SQLException {
        String update = "UPDATE payment_operation SET state = 'SUCCEEDED', psp_charge_id = ?, response = jsonb_build_object('status', 201, 'body', 'charged ' || ?::text), updated_at = now() WHERE id = ?"
                        + (guarded ? " AND state IN ('PENDING', 'UNKNOWN')" : "");
        if (exec(c, update, charge.id(), charge.id(), id) == 0) return false;
        exec(c, "INSERT INTO ledger_entry (operation_id, account, debit_minor) VALUES (?, 'psp:A:clearing', ?)", id, charge.amount());
        exec(c, "INSERT INTO ledger_entry (operation_id, account, credit_minor) VALUES (?, 'merchant:7:payable', ?)", id, charge.amount());
        exec(c, "INSERT INTO outbox (operation_id, event) VALUES (?, 'PaymentSucceeded')", id);
        return true;
    }

    /** Copies one legacy row into payment_operation (same response, charge ID and age), with its ledger rows. */
    static void adoptLegacy(Connection c, String key) throws SQLException {
        UUID id = operationId(MERCHANT, key);
        boolean adopted = queryString(c, """
            INSERT INTO payment_operation (id, merchant_id, idempotency_key, request_hash, amount_minor, currency, state, psp, psp_charge_id, response, created_at)
            SELECT ?, merchant_id, idempotency_key, request_hash, amount_minor, currency, 'SUCCEEDED', 'PSP A', psp_charge_id, response, created_at
            FROM legacy_idempotency WHERE merchant_id = ? AND idempotency_key = ?
            ON CONFLICT DO NOTHING RETURNING id""", id, MERCHANT, key) != null;          // any unique constraint: see part 1
        if (adopted) {
            exec(c, "INSERT INTO ledger_entry (operation_id, account, debit_minor) SELECT id, 'psp:A:clearing', amount_minor FROM payment_operation WHERE id = ?", id);
            exec(c, "INSERT INTO ledger_entry (operation_id, account, credit_minor) SELECT id, 'merchant:7:payable', amount_minor FROM payment_operation WHERE id = ?", id);
        }
    }

    // ------------------------------------------------------------------ routing during the cutover

    static final class Router {
        final OldHandler old;
        final NewHandler fresh;
        Router(OldHandler old, NewHandler fresh) { this.old = old; this.fresh = fresh; }

        Response pay(String key, Request r) throws SQLException {
            // Count the request in and read the handler in one statement, so a drain can't miss it. Under READ COMMITTED,
            // an UPDATE that waited for the switch's row lock re-checks its WHERE clause against the new handler.
            String handler = withConnection(c -> queryString(c,
                    "UPDATE merchant_handler SET in_flight = in_flight + 1 WHERE merchant_id = ? AND handler <> 'DRAINING' RETURNING handler", MERCHANT));
            if (handler == null) return new Response(503, "switching handlers, Retry-After: 1");
            try {
                return handler.equals("OLD") ? old.pay(key, r) : fresh.pay(key, r);
            } finally {
                withConnection(c -> exec(c, "UPDATE merchant_handler SET in_flight = in_flight - 1 WHERE merchant_id = ?", MERCHANT));
            }
        }

        static void setHandler(String handler) throws SQLException {
            withConnection(c -> exec(c, "INSERT INTO merchant_handler (merchant_id, handler) VALUES (?, ?) ON CONFLICT (merchant_id) DO UPDATE SET handler = EXCLUDED.handler", MERCHANT, handler));
        }

        static void drain() throws SQLException {
            setHandler("DRAINING");
            while (withConnection(c -> Integer.parseInt(queryString(c, "SELECT in_flight FROM merchant_handler WHERE merchant_id = ?", MERCHANT))) > 0)
                sleepMillis(10);
        }

        /** Forward: stop taking requests, wait for the old handler's in-flight ones, copy everything it completed, switch. */
        static void drainBackfillSwitch() throws SQLException {
            drain();
            inTransaction(c -> {
                for (String key : queryStrings(c, "SELECT idempotency_key FROM legacy_idempotency WHERE merchant_id = ?", MERCHANT))
                    adoptLegacy(c, key);
                return null;
            });
            setHandler("NEW");
        }

        /** Back: stop taking requests, wait for in-flight ones, let the reconciler settle every unresolved operation, switch. */
        static void drainReconcileSwitchBack(Psp psp) throws SQLException {
            drain();
            for (String s : withConnection(c -> queryStrings(c, "SELECT id FROM payment_operation WHERE state IN ('PENDING', 'UNKNOWN')"))) {
                UUID id = UUID.fromString(s);                                       // nothing is in flight, so nobody owns these
                long amount = withConnection(c -> Long.parseLong(queryString(c, "SELECT amount_minor FROM payment_operation WHERE id = ?", id)));
                Charge charge = psp.charge(id.toString(), id.toString(), amount);   // the same key: the PSP replays
                inTransaction(c -> recordIn(c, id, charge, true));
            }
            setHandler("OLD");
        }
    }

    // ------------------------------------------------------------------ the experiments

    public static void main(String[] args) throws Exception {
        url = args.length > 0 ? args[0] : System.getenv("PG_URL");
        if (url == null) {
            System.err.println("usage: java -cp postgresql.jar PaymentsPostgres.java \"jdbc:postgresql://host:port/some_lab?user=...&password=...\"");
            System.exit(2);
        }
        try (Connection c = DriverManager.getConnection(url)) {
            String db = queryString(c, "SELECT current_database()");
            if (!db.endsWith("_lab")) {
                System.err.println("refusing to touch database " + db + ": use a throwaway database whose name ends in _lab");
                System.exit(1);
            }
            String version = queryString(c, "SHOW server_version");
            c.createStatement().execute(SCHEMA);
            System.out.printf("PostgreSQL %s, database %s: schema payments_lab recreated%n%n", version.split(" ")[0], db);
        }
        pool = new ArrayBlockingQueue<>(16);
        for (int i = 0; i < 16; i++) {
            Connection c = DriverManager.getConnection(url);
            c.createStatement().execute("SET search_path = payments_lab");
            pool.add(c);
        }
        try {
            part1();
            part2();
            part3();
            part4();
            part5();
            part6();
        } finally {
            for (Connection c : pool) c.close();
        }
        System.out.println();
        System.out.println(failures.isEmpty() ? "All checks passed." : "CHECKS FAILED:\n  " + String.join("\n  ", failures));
        if (!failures.isEmpty()) System.exit(1);
    }

    static void part1() throws Exception {
        System.out.println("1. Four identical requests at once for each of 500 checkouts, on 32 threads sharing 16 connections");
        System.out.printf("   %-61s %11s %17s   %s%n", "handler", "PSP charges", "charged 2+ times", "responses");
        for (int variant = 0; variant < 3; variant++) {
            reset();
            Psp psp = new Psp();
            OldHandler old = new OldHandler(psp);
            NewHandler fresh = new NewHandler(psp);
            if (variant == 1) fresh.conflictTarget = "(merchant_id, idempotency_key)";
            boolean useOld = variant == 0;
            List<Response> responses = burst(key -> useOld ? old.pay(key, requestFor(key)) : fresh.pay(key, requestFor(key)), 500, 4);
            String label = switch (variant) {
                case 0 -> "check, charge, then save";
                case 1 -> "claim: ON CONFLICT (merchant_id, idempotency_key) DO NOTHING";
                default -> "claim: ON CONFLICT DO NOTHING (any unique constraint)";
            };
            System.out.printf("   %-61s %,11d %,17d   %s%n", label, psp.charges.size(), psp.checkoutsChargedTwice(), counts(responses));
            if (variant > 0)
                expect("part 1: claiming first charged each checkout once", psp.charges.size() == 500 && psp.checkoutsChargedTwice() == 0);
            if (variant == 2) {
                expect("part 1: no claim failed with an error", responses.stream().noneMatch(r -> r.status() == 500));
                sqlChecks("part 1");
            }
        }
        System.out.println("   SQL checks on the last run: ledger balanced, one debit per success, one outbox row per success: "
                           + (failures.stream().noneMatch(f -> f.startsWith("part 1")) ? "ok" : "FAILED"));
    }

    static void part2() throws Exception {
        System.out.println();
        System.out.println("2. The process dies inside the outcome transaction, after the state change and one ledger row");
        reset();
        Psp psp = new Psp();
        NewHandler fresh = new NewHandler(psp);
        UUID id = operationId(MERCHANT, "key-A");
        withConnection(c -> exec(c, "INSERT INTO payment_operation (id, merchant_id, idempotency_key, request_hash, amount_minor, currency, state, psp) VALUES (?, ?, 'key-A', ?, 2500, 'EUR', 'PENDING', 'PSP A')",
                                 id, MERCHANT, requestFor("key-A").hash()));
        Charge charge = psp.charge(id.toString(), id.toString(), 2500);
        Connection dying = DriverManager.getConnection(url);                     // not from the pool: it's about to die
        dying.createStatement().execute("SET search_path = payments_lab");
        dying.setAutoCommit(false);
        exec(dying, "UPDATE payment_operation SET state = 'SUCCEEDED', psp_charge_id = ? WHERE id = ? AND state IN ('PENDING', 'UNKNOWN')", charge.id(), id);
        exec(dying, "INSERT INTO ledger_entry (operation_id, account, debit_minor) VALUES (?, 'psp:A:clearing', 2500)", id);
        dying.close();                                                            // no commit: the server rolls it back
        String afterCrash = stateLine(id);
        System.out.println("   after the crash:                          " + afterCrash);
        expect("part 2: nothing from the dead transaction survived", afterCrash.equals("state PENDING, ledger rows 0, outbox rows 0"));
        fresh.record(id, psp.charge(id.toString(), id.toString(), 2500));        // the reconciler re-sends with the same key
        String afterReconcile = stateLine(id) + ", PSP charges " + psp.charges.size();
        System.out.println("   after the reconciler re-sent the same key: " + afterReconcile);
        expect("part 2: the re-drive recorded it once", afterReconcile.equals("state SUCCEEDED, ledger rows 2, outbox rows 1, PSP charges 1"));
        sqlChecks("part 2");
    }

    static void part3() throws Exception {
        System.out.println();
        System.out.println("3. Two writers record the same outcome at the same moment (the request and the reconciler), for 200 payments");
        System.out.printf("   %-59s %11s %19s%n", "the outcome update", "ledger rows", "payments debited 2x");
        for (boolean guarded : new boolean[] {false, true}) {
            reset();
            List<UUID> ids = new ArrayList<>();
            withConnection(c -> {
                for (int i = 0; i < 200; i++) {
                    UUID id = operationId(MERCHANT, "race-" + i);
                    ids.add(id);
                    exec(c, "INSERT INTO payment_operation (id, merchant_id, idempotency_key, request_hash, amount_minor, currency, state, psp) VALUES (?, ?, ?, 'h', 2500, 'EUR', 'PENDING', 'PSP A')",
                         id, MERCHANT, "race-" + i);
                }
                return null;
            });
            try (ExecutorService pool32 = Executors.newFixedThreadPool(32)) {
                for (UUID id : ids) {
                    CyclicBarrier together = new CyclicBarrier(2);
                    Charge charge = new Charge("ch_" + id.toString().substring(0, 6), id.toString(), 2500);
                    for (int w = 0; w < 2; w++)
                        pool32.submit(() -> {
                            together.await();
                            return inTransaction(c -> recordIn(c, id, charge, guarded));
                        });
                }
            }
            long ledgerRows = withConnection(c -> Long.parseLong(queryString(c, "SELECT count(*) FROM ledger_entry")));
            long doubled = withConnection(c -> Long.parseLong(queryString(c,
                    "SELECT count(*) FROM (SELECT operation_id FROM ledger_entry WHERE debit_minor > 0 GROUP BY operation_id HAVING count(*) > 1) d")));
            String label = guarded ? "UPDATE ... WHERE id = ? AND state IN ('PENDING','UNKNOWN')" : "UPDATE ... WHERE id = ?";
            System.out.printf("   %-59s %,11d %,19d%n", label, ledgerRows, doubled);
            if (guarded) {
                expect("part 3: the guarded update let exactly one writer through", ledgerRows == 400 && doubled == 0);
                sqlChecks("part 3");
            }
        }
    }

    static void part4() throws Exception {
        System.out.println();
        System.out.println("4. Three reconcilers share 300 unresolved payments: each claims 20 with FOR UPDATE SKIP LOCKED and a lease,");
        System.out.println("   commits, then calls the PSP with no transaction open");
        reset();
        withConnection(c -> exec(c, """
            INSERT INTO payment_operation (id, merchant_id, idempotency_key, request_hash, amount_minor, currency, state, psp, created_at)
            SELECT md5('unresolved-' || n)::uuid, 7, 'unresolved-' || n, 'h', 2500, 'EUR', 'UNKNOWN', 'PSP A', now() - n * interval '1 second'
            FROM generate_series(1, 300) n"""));
        Psp psp = new Psp();
        Map<UUID, AtomicInteger> handled = new ConcurrentHashMap<>();
        int[] perReconciler = new int[3];
        try (ExecutorService reconcilers = Executors.newFixedThreadPool(3)) {
            for (int r = 0; r < 3; r++) {
                int me = r;
                reconcilers.submit(() -> {
                    while (true) {
                        List<String> batch = inTransaction(c -> queryStrings(c, """
                            UPDATE payment_operation SET lease_until = now() + interval '30 seconds'
                            WHERE id IN (SELECT id FROM payment_operation
                                         WHERE state = 'UNKNOWN' AND (lease_until IS NULL OR lease_until < now())
                                         ORDER BY created_at LIMIT 20 FOR UPDATE SKIP LOCKED)
                            RETURNING id"""));
                        if (batch.isEmpty()) return null;
                        for (String s : batch) {
                            UUID id = UUID.fromString(s);
                            handled.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
                            perReconciler[me]++;
                            Charge charge = psp.charge(id.toString(), id.toString(), 2500);
                            inTransaction(c -> recordIn(c, id, charge, true));
                        }
                    }
                });
            }
        }
        long twice = handled.values().stream().filter(n -> n.get() > 1).count();
        long unresolved = withConnection(c -> Long.parseLong(queryString(c, "SELECT count(*) FROM payment_operation WHERE state <> 'SUCCEEDED'")));
        System.out.printf("   payments handled: %d in all, by 3 reconcilers; handled twice: %d; still unresolved: %d; PSP charges: %d%n",
                          handled.size(), twice, unresolved, psp.charges.size());
        expect("part 4: SKIP LOCKED and the lease gave each payment to one reconciler",
               handled.size() == 300 && twice == 0 && unresolved == 0 && psp.charges.size() == 300
               && Arrays.stream(perReconciler).allMatch(n -> n > 0));
        sqlChecks("part 4");
    }

    static void part5() throws Exception {
        System.out.println();
        System.out.println("5. Cutover from the old idempotency table: the old handler completed 100 payments, then their clients retry");
        System.out.printf("   %-61s %11s   %s%n", "how the merchant moves to the new handler", "PSP charges", "the retries got");
        for (int variant = 0; variant < 3; variant++) {
            reset();
            Psp psp = new Psp();
            NewHandler fresh = new NewHandler(psp);
            Router router = new Router(new OldHandler(psp), fresh);
            Router.setHandler("OLD");
            for (int i = 0; i < 100; i++) router.pay("old-" + i, requestFor("old-" + i));
            String label;
            if (variant == 0) {
                label = "switch; the new handler treats every key as new";
                Router.setHandler("NEW");
            } else {
                label = variant == 1 ? "drain, copy the old rows (response, charge, age), switch"
                                     : "  ... then a row the copy missed (legacy lookup on)";
                Router.drainBackfillSwitch();
                if (variant == 2) {                                                 // a straggler written after the copy
                    Charge late = psp.charge(null, "old-late", 2500);
                    withConnection(c -> exec(c, """
                        INSERT INTO legacy_idempotency (merchant_id, idempotency_key, request_hash, amount_minor, currency, response, psp_charge_id, created_at)
                        VALUES (?, 'old-late', ?, 2500, 'EUR', jsonb_build_object('status', 201, 'body', 'charged ' || ?::text), ?, now() - interval '1 hour')""",
                        MERCHANT, requestFor("old-late").hash(), late.id(), late.id()));
                    fresh.legacyLookup = true;
                }
            }
            List<Response> retries = new ArrayList<>();
            for (int i = 0; i < 100; i++) retries.add(router.pay("old-" + i, requestFor("old-" + i)));
            if (variant == 2) retries.add(router.pay("old-late", requestFor("old-late")));
            System.out.printf("   %-61s %,11d   %s%n", label, psp.charges.size(), counts(retries));
            if (variant > 0) {
                int expected = variant == 1 ? 100 : 101;
                expect("part 5: the cutover charged nobody twice", psp.charges.size() == expected && psp.checkoutsChargedTwice() == 0);
                long kept = withConnection(c -> Long.parseLong(queryString(c, """
                    SELECT count(*) FROM payment_operation o JOIN legacy_idempotency l USING (merchant_id, idempotency_key)
                    WHERE o.state = 'SUCCEEDED' AND o.psp_charge_id = l.psp_charge_id AND o.response = l.response AND o.created_at = l.created_at""")));
                expect("part 5: the copy kept each payment's response, charge ID and age", kept == expected);
                expect("part 5: every retry replayed the original", retries.stream().allMatch(r -> r.body().endsWith("(replayed)")));
                sqlChecks("part 5");
            }
        }

        reset();
        Psp psp = new Psp();
        psp.latencyMs = 300;
        Router router = new Router(new OldHandler(psp), new NewHandler(psp));
        Router.setHandler("OLD");
        List<Response> retries = new CopyOnWriteArrayList<>();
        try (ExecutorService clients = Executors.newFixedThreadPool(64)) {
            for (int i = 0; i < 50; i++) {
                String key = "inflight-" + i;
                clients.submit(() -> router.pay(key, requestFor(key)));             // 50 old requests, still charging
            }
            sleepMillis(100);
            Future<?> cutover = clients.submit(() -> { Router.drainBackfillSwitch(); return null; });
            sleepMillis(50);
            List<Future<Response>> firstTries = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                String key = "inflight-" + i;
                firstTries.add(clients.submit(() -> router.pay(key, requestFor(key))));
            }
            for (Future<Response> f : firstTries) retries.add(f.get());
            cutover.get();
            for (int i = 0; i < 50; i++) retries.add(router.pay("inflight-" + i, requestFor("inflight-" + i)));   // after Retry-After
        }
        System.out.printf("   %-61s %,11d   %s%n", "50 retries while 50 old requests are charging: drain first", psp.charges.size(), counts(retries));
        expect("part 5: retries during the drain waited, then replayed", psp.charges.size() == 50 && psp.checkoutsChargedTwice() == 0
               && retries.stream().filter(r -> r.status() == 503).count() == 50
               && retries.stream().filter(r -> r.body().endsWith("(replayed)")).count() == 50);
        sqlChecks("part 5");
    }

    static void part6() throws Exception {
        System.out.println();
        System.out.println("6. Rollback: the new handler completed 100 payments and left 1 UNKNOWN (the PSP charged it, but the answer");
        System.out.println("   was lost); then the merchant goes back to the old handler, and all 101 clients retry");
        System.out.printf("   %-61s %11s   %s%n", "how the merchant moves back", "PSP charges", "the retries got");
        for (int variant = 0; variant < 3; variant++) {
            reset();
            Psp psp = new Psp();
            OldHandler old = new OldHandler(psp);
            Router router = new Router(old, new NewHandler(psp));
            Router.setHandler("NEW");
            for (int i = 0; i < 100; i++) router.pay("new-" + i, requestFor("new-" + i));
            UUID lost = operationId(MERCHANT, "new-lost");
            withConnection(c -> exec(c, "INSERT INTO payment_operation (id, merchant_id, idempotency_key, request_hash, amount_minor, currency, state, psp) VALUES (?, ?, 'new-lost', ?, 2500, 'EUR', 'UNKNOWN', 'PSP A')",
                                     lost, MERCHANT, requestFor("new-lost").hash()));
            psp.charge(lost.toString(), lost.toString(), 2500);
            String label = switch (variant) {
                case 0 -> "switch back; the old handler reads only its own table";
                case 1 -> "switch back; the old handler also reads payment_operation";
                default -> "  ... after a drain, in which the reconciler settled the rest";
            };
            old.readShim = variant > 0;
            if (variant < 2) Router.setHandler("OLD");
            else Router.drainReconcileSwitchBack(psp);
            List<Response> retries = new ArrayList<>();
            for (int i = 0; i < 100; i++) retries.add(router.pay("new-" + i, requestFor("new-" + i)));
            retries.add(router.pay("new-lost", requestFor("new-lost")));
            System.out.printf("   %-61s %,11d   %s%n", label, psp.charges.size(), counts(retries));
            if (variant == 1)
                System.out.println("       (the old code has no reconciler, so that client sees 202 until someone settles it by hand)");
            if (variant == 0)
                expect("part 6: without the shim, the old handler charged every checkout again", psp.checkoutsChargedTwice() == 101);
            else
                expect("part 6: going back with the shim charged nobody twice", psp.charges.size() == 101 && psp.checkoutsChargedTwice() == 0);
            if (variant == 2) {
                expect("part 6: after the drain, every retry replayed the original",
                       retries.stream().allMatch(r -> r.body().endsWith("(replayed)")));
                sqlChecks("part 6");
            }
        }
    }

    /** The invariant queries from Chapter 36 §9: each must return no rows. */
    static void sqlChecks(String part) throws SQLException {
        Map<String, String> queries = new LinkedHashMap<>();
        queries.put("ledger balanced per payment", "SELECT operation_id FROM ledger_entry GROUP BY operation_id HAVING sum(debit_minor) <> sum(credit_minor)");
        queries.put("one debit for the full amount per success", """
            SELECT o.id FROM payment_operation o LEFT JOIN ledger_entry e ON e.operation_id = o.id AND e.debit_minor > 0
            WHERE o.state = 'SUCCEEDED' GROUP BY o.id, o.amount_minor
            HAVING count(e.id) <> 1 OR coalesce(sum(e.debit_minor), 0) <> o.amount_minor""");
        queries.put("no ledger rows without a success", """
            SELECT e.operation_id FROM ledger_entry e JOIN payment_operation o ON o.id = e.operation_id WHERE o.state <> 'SUCCEEDED'""");
        queries.put("one outbox row per success", """
            SELECT o.id FROM payment_operation o LEFT JOIN outbox x ON x.operation_id = o.id
            WHERE o.state = 'SUCCEEDED' AND o.psp_charge_id NOT IN (SELECT psp_charge_id FROM legacy_idempotency)
            GROUP BY o.id HAVING count(x.id) <> 1""");
        for (var q : queries.entrySet()) {
            List<String> rows = withConnection(c -> queryStrings(c, q.getValue()));
            expect(part + ": " + q.getKey(), rows.isEmpty());
        }
    }

    static String stateLine(UUID id) throws SQLException {
        return withConnection(c -> "state " + queryString(c, "SELECT state FROM payment_operation WHERE id = ?", id)
                + ", ledger rows " + queryString(c, "SELECT count(*) FROM ledger_entry WHERE operation_id = ?", id)
                + ", outbox rows " + queryString(c, "SELECT count(*) FROM outbox WHERE operation_id = ?", id));
    }

    // ------------------------------------------------------------------ helpers

    interface SqlWork<T> { T run(Connection c) throws SQLException; }
    interface Payment { Response pay(String key) throws Exception; }

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

    static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
    }

    static int exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        }
    }

    static String queryString(Connection c, String sql, Object... args) throws SQLException {
        List<String> rows = queryStrings(c, sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static List<String> queryStrings(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            List<String> rows = new ArrayList<>();
            if (ps.execute()) {
                try (ResultSet rs = ps.getResultSet()) { while (rs.next()) rows.add(rs.getString(1)); }
            }
            return rows;
        }
    }

    static List<Response> burst(Payment payment, int checkouts, int copies) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        try (ExecutorService threads = Executors.newFixedThreadPool(32)) {
            for (int i = 0; i < checkouts; i++) {
                String key = "key-" + i;
                for (int k = 0; k < copies; k++)
                    futures.add(threads.submit(() -> { start.await(); return payment.pay(key); }));
            }
            start.countDown();
        }
        List<Response> responses = new ArrayList<>();
        for (Future<Response> f : futures) responses.add(f.get());
        return responses;
    }

    static String counts(List<Response> responses) {
        Map<String, Long> counts = new TreeMap<>();
        for (Response r : responses) {
            String label = switch (r.status()) {
                case 201 -> r.body().endsWith("(replayed)") ? "201 replayed" : "201 charged";
                case 202 -> "202 processing";
                case 409 -> "409 in progress";
                case 500 -> "500 duplicate key";
                case 503 -> "503 switching";
                default -> String.valueOf(r.status());
            };
            counts.merge(label, 1L, Long::sum);
        }
        return counts.entrySet().stream().map(e -> String.format("%,d x %s", e.getValue(), e.getKey())).collect(Collectors.joining(", "));
    }

    static void expect(String what, boolean ok) {
        if (!ok) failures.add(what);
    }

    static void sleepMillis(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
