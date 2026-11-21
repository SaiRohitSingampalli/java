/*
 * Lab:       Payment idempotency, done wrong and done right: retries, crashes, lost answers, failovers and a cutover
 * Chapters:  23 §7 (idempotency keys) and 36 (the payments case study)
 * Run:       java PaymentIdempotency.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-21
 * Sources:   Stripe, "Idempotent requests": https://docs.stripe.com/api/idempotent_requests
 *            Stripe, "Search": https://docs.stripe.com/search (search is eventually consistent)
 *            IETF draft "The Idempotency-Key HTTP Header Field": https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/
 * Output:    part 1 varies a little with thread timing; parts 2 to 8 are deterministic (part 8 relies on gaps
 *            of 50 ms or more between threads). Exits with status 1 if any check on a correct configuration fails.
 *
 * A simulated payment processor (PSP) that deduplicates on its own idempotency key and forgets keys after 24 hours,
 * whose search lags behind its charges, and whose settlement report is authoritative a day and a bit later. An
 * operations table with a unique constraint on the client's Idempotency-Key, a ledger, and an optional charge
 * journal kept outside the database. Two handlers: "check, charge, then save" (the order the first version of
 * Chapter 23 showed) and "claim the key first, charge with a stable PSP key, reconcile what stays unknown".
 */
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class PaymentIdempotency {

    static final long SECOND = 1_000L, MINUTE = 60_000L, HOUR = 3_600_000L;
    static final long PSP_KEY_RETENTION = 24 * HOUR;   // a PSP may prune its idempotency keys after 24 hours
    static final long LEASE = 30 * SECOND;             // a PENDING operation older than this has lost its caller
    static final long SEARCH_LAG = 5 * MINUTE;         // the PSP's search may not show a charge for this long
    static final long REPORT_DELAY = 30 * HOUR;        // the settlement report for a moment is final this much later
    static final String MERCHANT = "m7";

    /** Simulated time for leases, key retention and reports. The PSP's latency is real (2 ms), so races are real. */
    static final class Clock {
        private final AtomicLong millis = new AtomicLong();
        long now() { return millis.get(); }
        void advance(long ms) { millis.addAndGet(ms); }
    }

    /** The operation's ID is derived from the merchant and the client's key, so a lost row comes back with the same ID. */
    static String operationId(String key) {
        return "op-" + UUID.nameUUIDFromBytes((MERCHANT + "|" + key).getBytes(StandardCharsets.UTF_8)).toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ the payment processor

    static final class PspTimeout extends Exception {
        PspTimeout() { super("timed out waiting for the PSP", null, false, false); }
    }

    /** The process dies at this point; whatever wasn't committed is lost. */
    static final class Crash extends RuntimeException {
        Crash() { super("process died", null, false, false); }
    }

    record Charge(String id, String reference, long amount, long chargedAt) {}

    /** What the settlement report says about one reference: nothing yet, or an authoritative answer. */
    record Settlement(boolean isFinal, Optional<Charge> charge) {}

    static final class Psp {
        private record KeyEntry(long firstSeen, CompletableFuture<Charge> result) {}

        final String name;
        final Clock clock;
        final boolean supportsKeys;
        volatile long latencyMs = 2;
        final List<Charge> charges = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, KeyEntry> keys = new ConcurrentHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger(4000);
        final AtomicInteger loseNextResponses = new AtomicInteger();   // fault injection: charge, then time out

        Psp(String name, Clock clock, boolean supportsKeys) { this.name = name; this.clock = clock; this.supportsKeys = supportsKeys; }
        Psp(Clock clock) { this("PSP A", clock, true); }

        /** Charges the card. With a key, a repeat within the retention window replays the first result. */
        Charge charge(String idempotencyKey, String reference, long amount) throws PspTimeout {
            if (idempotencyKey != null && !supportsKeys) throw new IllegalArgumentException(name + " has no idempotency keys");
            Charge result = idempotencyKey == null ? execute(reference, amount) : executeOnce(idempotencyKey, reference, amount);
            if (loseNextResponses.getAndUpdate(n -> Math.max(0, n - 1)) > 0) throw new PspTimeout();   // the card WAS charged
            return result;
        }

        private Charge executeOnce(String key, String reference, long amount) {
            while (true) {
                KeyEntry mine = new KeyEntry(clock.now(), new CompletableFuture<>());
                KeyEntry previous = keys.putIfAbsent(key, mine);
                if (previous != null && clock.now() - previous.firstSeen() > PSP_KEY_RETENTION) {   // the key was pruned
                    if (!keys.replace(key, previous, mine)) continue;
                    previous = null;
                }
                if (previous != null) return previous.result().join();
                Charge charge = execute(reference, amount);
                mine.result().complete(charge);
                return charge;
            }
        }

        private Charge execute(String reference, long amount) {
            sleepMillis(latencyMs);                                              // network and processing
            Charge charge = new Charge("ch_" + sequence.incrementAndGet(), reference, amount, clock.now());
            charges.add(charge);
            return charge;
        }

        /** "Search payments by our reference". Eventually consistent: a charge younger than SEARCH_LAG is invisible. */
        List<Charge> search(String reference) {
            synchronized (charges) {
                return charges.stream().filter(c -> c.reference().equals(reference) && clock.now() - c.chargedAt() >= SEARCH_LAG).toList();
            }
        }

        /** The settlement report covering an operation created at {@code since}: authoritative once it's final. */
        Settlement settlement(String reference, long since) {
            if (clock.now() < since + REPORT_DELAY) return new Settlement(false, Optional.empty());
            synchronized (charges) {
                return new Settlement(true, charges.stream().filter(c -> c.reference().equals(reference)).findFirst());
            }
        }
    }

    // ------------------------------------------------------------------ requests and responses

    record Request(String customer, long amount, String currency) {
        String fingerprint() { return customer + "|" + amount + "|" + currency; }
    }

    record Response(int status, String body) {
        @Override public String toString() { return status + " " + body; }
    }

    // ------------------------------------------------------------------ version 1: check, charge, then save

    /** What the old handler saves: its idempotency table, which the migration has to carry over. */
    record LegacyRecord(String fingerprint, long amount, String chargeId, long createdAt) {
        String body() { return "charged " + chargeId; }
    }

    static final class CheckThenSave {
        final Psp psp;
        final Map<String, LegacyRecord> saved = new ConcurrentHashMap<>();    // UNIQUE (idempotency_key)
        volatile boolean crashAfterCharge;

        CheckThenSave(Psp psp) { this.psp = psp; }

        Response pay(String key, Request request) {
            LegacyRecord previous = saved.get(key);                            // 1. look for a saved response
            if (previous != null)
                return previous.fingerprint().equals(request.fingerprint()) ? new Response(201, previous.body() + " (replayed)")
                                                                            : new Response(422, "idempotency_key_reused");
            long startedAt = psp.clock.now();
            Charge charge;
            try {
                charge = psp.charge(null, key, request.amount());              // 2. charge the card (no PSP key)
            } catch (PspTimeout e) {
                return new Response(504, "gateway timeout");
            }
            if (crashAfterCharge) { crashAfterCharge = false; throw new Crash(); }
            LegacyRecord mine = new LegacyRecord(request.fingerprint(), request.amount(), charge.id(), startedAt);
            if (saved.putIfAbsent(key, mine) != null)
                return new Response(500, "duplicate key value violates unique constraint");   // 3. save: too late
            return new Response(201, mine.body());
        }
    }

    // ------------------------------------------------------------------ version 2: claim the key first

    enum State { PENDING, SUCCEEDED, UNKNOWN, NOT_CHARGED }

    record Operation(String id, String key, String fingerprint, long amount, State state, String chargeId, long createdAt, String psp) {
        Operation with(State newState, String newChargeId) {
            return new Operation(id, key, fingerprint, amount, newState, newChargeId, createdAt, psp);
        }
    }

    record LedgerEntry(String operationId, String account, long debit, long credit) {}

    /** Written before any money moves, to a system that survives a database failover (a replicated log, say). */
    record JournalEntry(String operationId, String key, String fingerprint, long amount, String psp, long createdAt) {}

    enum PspKeys { ONE_PER_OPERATION, NEW_PER_ATTEMPT }

    enum EmptySearch { MEANS_NOT_CHARGED, PROVES_NOTHING }

    static final class ClaimFirst {
        final Clock clock;
        final Psp psp;
        final PspKeys pspKeys;
        boolean journalEnabled;                                                 // the charge journal (part 7)
        EmptySearch emptySearch = EmptySearch.PROVES_NOTHING;                   // what an empty search means (part 6)
        Map<String, LegacyRecord> legacy;                                       // the old handler's table (part 8)
        final Map<String, Operation> operations = new ConcurrentHashMap<>();   // UNIQUE (merchant_id, idempotency_key)
        final List<LedgerEntry> ledger = Collections.synchronizedList(new ArrayList<>());
        final Map<String, JournalEntry> journal = new ConcurrentHashMap<>();    // outside the database
        volatile boolean crashAfterCharge, crashBeforeCharge;

        ClaimFirst(Clock clock, Psp psp, PspKeys pspKeys) { this.clock = clock; this.psp = psp; this.pspKeys = pspKeys; }

        Response pay(String key, Request request) {
            Operation existing = operations.get(key);
            if (existing == null) existing = adoptLegacy(key);                 // a payment the old handler completed
            if (existing == null) existing = recoverFromJournal(key);          // a row the database lost
            if (existing != null) return answer(existing, request);
            Operation fresh = new Operation(operationId(key), key, request.fingerprint(), request.amount(), State.PENDING,
                                            null, clock.now(), psp.name);
            Operation raced = operations.putIfAbsent(key, fresh);              // INSERT ... ON CONFLICT DO NOTHING, commit
            if (raced != null) return answer(raced, request);
            if (journalEnabled)                                                 // durable before the PSP sees anything
                journal.putIfAbsent(fresh.id(), new JournalEntry(fresh.id(), key, fresh.fingerprint(), fresh.amount(),
                                                                 fresh.psp(), fresh.createdAt()));
            if (crashBeforeCharge) { crashBeforeCharge = false; throw new Crash(); }
            return drive(fresh);                                                // no transaction open during the PSP call
        }

        Response answer(Operation op, Request request) {
            if (!op.fingerprint().equals(request.fingerprint())) return new Response(422, "idempotency_key_reused");
            return responseFor(op);
        }

        static Response responseFor(Operation op) {
            return switch (op.state()) {
                case SUCCEEDED   -> new Response(201, "charged " + op.chargeId() + " (replayed)");
                case PENDING     -> new Response(409, "idempotency_key_in_use, Retry-After: 1");
                case UNKNOWN     -> new Response(202, "processing, Retry-After: 5");
                case NOT_CHARGED -> new Response(402, "payment_failed: not charged");
            };
        }

        /** The new handler finds a payment the old one completed, and adopts it: same charge, same age, no PSP call. */
        Operation adoptLegacy(String key) {
            LegacyRecord old = legacy == null ? null : legacy.get(key);
            return old == null ? null : adopt(key, old);
        }

        Operation adopt(String key, LegacyRecord old) {
            Operation adopted = new Operation(operationId(key), key, old.fingerprint(), old.amount(), State.SUCCEEDED,
                                              old.chargeId(), old.createdAt(), psp.name);
            Operation winner = operations.putIfAbsent(key, adopted);
            if (winner != null) return winner;
            writeLedger(adopted);                                                // the migrated ledger rows, same transaction
            return adopted;
        }

        /** Copies every completed payment from the old table before the new handler takes traffic. */
        void backfill(Map<String, LegacyRecord> oldTable) {
            oldTable.forEach(this::adopt);
        }

        /** The row is gone, but the journal knows the operation, its age and its PSP: never treat it as new. */
        Operation recoverFromJournal(String key) {
            JournalEntry entry = journalEnabled ? journal.get(operationId(key)) : null;
            if (entry == null) return null;
            Operation recovered = new Operation(entry.operationId(), key, entry.fingerprint(), entry.amount(), State.UNKNOWN,
                                                null, entry.createdAt(), entry.psp());
            Operation winner = operations.putIfAbsent(key, recovered);
            return winner != null ? winner : recovered;
        }

        /** Calls the PSP for a claimed operation and records the outcome. Requests and the reconciler both use it. */
        Response drive(Operation op) {
            if (!psp.supportsKeys) return driveWithoutKeys(op);
            for (int attempt = 1; attempt <= 2; attempt++) {
                String pspKey = pspKeys == PspKeys.ONE_PER_OPERATION ? op.id() : op.id() + "-attempt-" + attempt;
                try {
                    Charge charge = psp.charge(pspKey, op.id(), op.amount());
                    if (crashAfterCharge) { crashAfterCharge = false; throw new Crash(); }
                    return record(op.key(), State.SUCCEEDED, charge.id());
                } catch (PspTimeout e) {
                    // The outcome is unknown, not failed: try again, and keep the key if the PSP should deduplicate.
                }
            }
            return markUnknown(op);
        }

        /** A PSP without idempotency keys: after a lost answer, look before charging again. */
        Response driveWithoutKeys(Operation op) {
            try {
                return record(op.key(), State.SUCCEEDED, psp.charge(null, op.id(), op.amount()).id());
            } catch (PspTimeout e) {
                List<Charge> found = psp.search(op.id());
                if (!found.isEmpty()) return record(op.key(), State.SUCCEEDED, found.get(0).id());
                if (emptySearch == EmptySearch.MEANS_NOT_CHARGED) {              // the mistake: absence taken as proof
                    try {
                        return record(op.key(), State.SUCCEEDED, psp.charge(null, op.id(), op.amount()).id());
                    } catch (PspTimeout again) { /* fall through */ }
                }
                return markUnknown(op);
            }
        }

        Response markUnknown(Operation op) {
            Operation current = operations.get(op.key());
            if (current.state() == State.PENDING) operations.replace(op.key(), current, current.with(State.UNKNOWN, null));
            return new Response(202, "processing, Retry-After: 5");
        }

        /** UPDATE ... SET state = ? WHERE id = ? AND state IN ('PENDING','UNKNOWN'), the ledger rows and an outbox row: one commit. */
        Response record(String key, State outcome, String chargeId) {
            while (true) {
                Operation current = operations.get(key);
                if (current.state() == State.SUCCEEDED || current.state() == State.NOT_CHARGED)
                    return responseFor(current);                                // another writer recorded it first
                Operation done = current.with(outcome, chargeId);
                if (operations.replace(key, current, done)) {                   // exactly one writer wins
                    if (outcome == State.SUCCEEDED) {
                        writeLedger(done);
                        return new Response(201, "charged " + chargeId);
                    }
                    return new Response(402, "payment_failed: not charged");
                }
            }
        }

        void writeLedger(Operation op) {
            ledger.add(new LedgerEntry(op.id(), "psp:clearing", op.amount(), 0));
            ledger.add(new LedgerEntry(op.id(), "merchant:7:payable", 0, op.amount()));
        }

        /** A failover that loses the most recent commits: the rows and their ledger entries. The journal survives. */
        void loseRecentCommits() {
            operations.clear();
            ledger.clear();
        }

        /**
         * Runs every few seconds. Settles UNKNOWN outcomes and PENDING operations whose caller died. Only a re-send
         * with the operation's own key while the PSP still keeps it may charge; after that, recovery only reads.
         */
        void reconcile(boolean lookUpAfterKeyRetention) {
            for (Operation op : List.copyOf(operations.values())) {
                boolean stuck = op.state() == State.UNKNOWN || (op.state() == State.PENDING && clock.now() - op.createdAt() > LEASE);
                if (!stuck) continue;
                boolean keyStillKnown = psp.supportsKeys && clock.now() - op.createdAt() < PSP_KEY_RETENTION - HOUR;
                if (keyStillKnown || (psp.supportsKeys && !lookUpAfterKeyRetention)) {
                    drive(op);                                                  // re-send with the SAME PSP key
                    continue;
                }
                List<Charge> found = psp.search(op.id());                       // never re-send once the key may be gone
                if (!found.isEmpty()) {
                    record(op.key(), State.SUCCEEDED, found.get(0).id());
                } else if (emptySearch == EmptySearch.MEANS_NOT_CHARGED) {
                    record(op.key(), State.NOT_CHARGED, null);
                } else {
                    Settlement report = psp.settlement(op.id(), op.createdAt());  // only an authoritative answer settles it
                    if (report.isFinal())
                        record(op.key(), report.charge().isPresent() ? State.SUCCEEDED : State.NOT_CHARGED,
                               report.charge().map(Charge::id).orElse(null));
                    // otherwise it stays UNKNOWN, and the alert on unresolved age decides when a person looks
                }
            }
        }
    }

    /** Routes a merchant's requests during the move from the old handler to the new one. */
    static final class Cutover {
        enum Mode { OLD, DRAINING, NEW }

        final CheckThenSave old;
        final ClaimFirst fresh;
        volatile Mode mode = Mode.OLD;
        final AtomicInteger oldInFlight = new AtomicInteger();

        Cutover(CheckThenSave old, ClaimFirst fresh) { this.old = old; this.fresh = fresh; }

        Response pay(String key, Request request) {
            if (mode == Mode.OLD) {
                oldInFlight.incrementAndGet();                                  // counted before the mode is re-read
                try {
                    if (mode == Mode.OLD) return old.pay(key, request);
                } finally {
                    oldInFlight.decrementAndGet();
                }
            }
            if (mode == Mode.DRAINING) return new Response(503, "switching handlers, Retry-After: 1");
            return fresh.pay(key, request);
        }

        /** The unsafe way: flip the flag and hope nothing is in flight. */
        void switchAtOnce() { mode = Mode.NEW; }

        /** Stop the old handler taking requests, wait for its in-flight ones, copy what it completed, then switch. */
        void drainBackfillSwitch() {
            mode = Mode.DRAINING;
            while (oldInFlight.get() > 0) sleepMillis(1);
            fresh.backfill(old.saved);
            mode = Mode.NEW;
        }
    }

    // ------------------------------------------------------------------ the experiments

    public static void main(String[] args) throws Exception {
        List<String> failures = new ArrayList<>();

        System.out.println("1. Four identical requests at once, for each of 1,000 payments (same Idempotency-Key)");
        System.out.printf("   %-26s %11s %18s   %s%n", "handler", "PSP charges", "charged 2+ times", "first responses");
        Psp naivePsp = new Psp(new Clock());
        CheckThenSave naive = new CheckThenSave(naivePsp);
        Map<String, List<Response>> naiveResponses = burst(naive::pay, 1_000, 4);
        System.out.printf("   %-26s %,11d %,18d   %s%n", "check, charge, then save", naivePsp.charges.size(),
                          chargedMoreThanOnce(naivePsp), summary(naiveResponses));
        Clock clock1 = new Clock();
        Psp psp1 = new Psp(clock1);
        ClaimFirst claim1 = new ClaimFirst(clock1, psp1, PspKeys.ONE_PER_OPERATION);
        Map<String, List<Response>> claimResponses = burst(claim1::pay, 1_000, 4);
        System.out.printf("   %-26s %,11d %,18d   %s%n", "claim the key first", psp1.charges.size(),
                          chargedMoreThanOnce(psp1), summary(claimResponses));
        List<Response> retried = new ArrayList<>();
        claimResponses.forEach((key, responses) -> responses.stream().filter(r -> r.status() == 409)
                .forEach(r -> retried.add(claim1.pay(key, requestFor(key)))));
        System.out.printf("   %,d clients that got 409 retried after Retry-After: %s%n", retried.size(), statusCounts(retried));
        check(failures, "part 1", claim1);

        System.out.println();
        System.out.println("2. The process dies after the PSP charged the card, before the outcome is saved");
        Psp naivePsp2 = new Psp(new Clock());
        CheckThenSave naive2 = new CheckThenSave(naivePsp2);
        naive2.crashAfterCharge = true;
        tryPay(() -> naive2.pay("key-A", requestFor("key-A")));
        Response naiveRetry = naive2.pay("key-A", requestFor("key-A"));
        System.out.printf("   check, charge, then save: nothing was saved, so the retry charged again (%s)    PSP charges: %d%n",
                          naiveRetry, naivePsp2.charges.size());
        long orphans = naivePsp2.charges.size() - naive2.saved.size();
        System.out.printf("     the daily settlement check finds %d charge with no saved payment -> refund it by hand%n", orphans);
        Clock clock2 = new Clock();
        Psp psp2 = new Psp(clock2);
        ClaimFirst claim2 = new ClaimFirst(clock2, psp2, PspKeys.ONE_PER_OPERATION);
        claim2.crashAfterCharge = true;
        tryPay(() -> claim2.pay("key-A", requestFor("key-A")));
        Response duringLease = claim2.pay("key-A", requestFor("key-A"));
        clock2.advance(LEASE + SECOND);
        claim2.reconcile(true);
        Response afterReconcile = claim2.pay("key-A", requestFor("key-A"));
        System.out.printf("   claim the key first: the operation stayed PENDING; a retry got %s;%n", duringLease);
        System.out.printf("     after the 30 s lease the reconciler re-sent it with the same PSP key and the PSP replayed%n");
        System.out.printf("     the charge; the next retry got %s    PSP charges: %d%n", afterReconcile, psp2.charges.size());
        check(failures, "part 2", claim2);

        System.out.println();
        System.out.println("3. The PSP charges the card but the response is lost (a timeout), so the service retries the PSP call");
        System.out.printf("   %-47s %11s%n", "PSP key on the retry", "PSP charges");
        for (PspKeys strategy : List.of(PspKeys.NEW_PER_ATTEMPT, PspKeys.ONE_PER_OPERATION)) {
            Clock clock = new Clock();
            Psp psp = new Psp(clock);
            ClaimFirst service = new ClaimFirst(clock, psp, strategy);
            psp.loseNextResponses.set(1);
            Response response = service.pay("key-B", requestFor("key-B"));
            String id = operationId("key-B");
            String label = strategy == PspKeys.NEW_PER_ATTEMPT ? "a new key per attempt (" + id + "-attempt-2)"
                                                               : "the operation's key again (" + id + ")";
            System.out.printf("   %-47s %11d   client got %s%n", label, psp.charges.size(), response);
            if (strategy == PspKeys.ONE_PER_OPERATION) check(failures, "part 3", service);
        }

        System.out.println();
        System.out.println("4. The same Idempotency-Key with a different amount");
        Clock clock4 = new Clock();
        Psp psp4 = new Psp(clock4);
        ClaimFirst claim4 = new ClaimFirst(clock4, psp4, PspKeys.ONE_PER_OPERATION);
        Response first = claim4.pay("key-C", new Request("cust-C", 2500, "EUR"));
        Response second = claim4.pay("key-C", new Request("cust-C", 4000, "EUR"));
        System.out.printf("   25.00 EUR -> %s; then 40.00 EUR with the same key -> %s    PSP charges: %d%n",
                          first, second, psp4.charges.size());
        check(failures, "part 4", claim4);

        System.out.println();
        System.out.println("5. An outcome still unknown 26 hours later (say the reconciler was down); the PSP keeps keys for 24 hours");
        System.out.printf("   %-40s %11s%n", "reconciler", "PSP charges");
        for (boolean lookUp : new boolean[] {false, true}) {
            Clock clock = new Clock();
            Psp psp = new Psp(clock);
            ClaimFirst service = new ClaimFirst(clock, psp, PspKeys.ONE_PER_OPERATION);
            psp.loseNextResponses.set(2);                                       // both attempts lose their response
            Response response = service.pay("key-D", requestFor("key-D"));
            clock.advance(26 * HOUR);
            service.reconcile(lookUp);
            Operation op = service.operations.get("key-D");
            String label = lookUp ? "looks the charge up by our reference" : "re-sends with the same key";
            System.out.printf("   %-40s %11d   client had %s; now %s %s%n", label, psp.charges.size(), response,
                              op.state(), op.chargeId());
            if (lookUp) check(failures, "part 5", service);
        }

        System.out.println();
        System.out.println("6. A PSP without idempotency keys: after a timeout, look the charge up before charging again");
        System.out.println("   (its search lags up to 5 minutes behind its charges)");
        System.out.printf("   %-49s %11s%n", "when the search finds nothing", "PSP charges");
        for (EmptySearch rule : EmptySearch.values()) {
            Clock clock = new Clock();
            Psp keyless = new Psp("PSP B", clock, false);
            ClaimFirst service = new ClaimFirst(clock, keyless, PspKeys.ONE_PER_OPERATION);
            service.emptySearch = rule;
            keyless.loseNextResponses.set(1);                                   // charged, but the answer is lost
            Response response = service.pay("key-E", requestFor("key-E"));
            String outcome;
            if (rule == EmptySearch.MEANS_NOT_CHARGED) {
                outcome = "client got " + response + "; the first charge is now a double charge";
            } else {
                clock.advance(10 * MINUTE);
                service.reconcile(true);
                Operation op = service.operations.get("key-E");
                outcome = "client got " + response + "; 10 min later the search found it: " + op.state() + " " + op.chargeId();
                check(failures, "part 6", service);
            }
            String label = rule == EmptySearch.MEANS_NOT_CHARGED ? "take it as \"not charged\" and charge again"
                                                                 : "keep it UNKNOWN until an answer is authoritative";
            System.out.printf("   %-49s %11d   %s%n", label, keyless.charges.size(), outcome);
        }

        System.out.println();
        System.out.println("7. The database fails over and loses the payment's row; the client retries with the same key");
        System.out.printf("   %-42s %12s %12s   %s%n", "recovery evidence", "retry after", "PSP charges", "outcome");
        record Recovery(String label, boolean journal, boolean reachedPsp, long retryAfter) {}
        for (Recovery r : List.of(new Recovery("the derived operation ID only", false, true, 10 * MINUTE),
                                  new Recovery("the derived operation ID only", false, true, 26 * HOUR),
                                  new Recovery("ID + a charge journal outside the database", true, true, 26 * HOUR),
                                  new Recovery("  ... the call never reached the PSP", true, false, 26 * HOUR))) {
            Clock clock = new Clock();
            Psp psp = new Psp(clock);
            ClaimFirst service = new ClaimFirst(clock, psp, PspKeys.ONE_PER_OPERATION);
            service.journalEnabled = r.journal();
            if (r.reachedPsp()) {
                service.pay("key-F", requestFor("key-F"));                     // charged; the client never heard back
            } else {
                service.crashBeforeCharge = true;                               // journal written, then the process died
                tryPay(() -> service.pay("key-F", requestFor("key-F")));
            }
            service.loseRecentCommits();                                        // the failover
            clock.advance(r.retryAfter());
            Response retry = service.pay("key-F", requestFor("key-F"));
            String outcome = retry.toString();
            if (r.journal()) {
                service.reconcile(true);
                Operation op = service.operations.get("key-F");
                if (op.state() == State.UNKNOWN) {
                    clock.advance(5 * HOUR);                                    // the settlement report becomes final
                    service.reconcile(true);
                    op = service.operations.get("key-F");
                    outcome += "; UNKNOWN until the settlement report was final, then " + op.state()
                               + " -> " + service.pay("key-F", requestFor("key-F"));
                } else {
                    outcome += ", then " + op.state() + " " + op.chargeId() + " (found by reference; nothing re-sent)";
                }
                check(failures, "part 7", service);
            } else {
                outcome += r.retryAfter() < PSP_KEY_RETENTION ? " (the PSP replayed it under the same key)"
                                                              : " (the PSP had pruned the key)";
            }
            System.out.printf("   %-42s %12s %12d   %s%n", r.label(), duration(r.retryAfter()), psp.charges.size(), outcome);
        }

        System.out.println();
        System.out.println("8. Moving a merchant from the old handler to the new one while its clients retry");
        System.out.printf("   %-55s %11s   %s%n", "cutover", "PSP charges", "the client's retry got");
        for (boolean backfill : new boolean[] {false, true}) {
            Clock clock = new Clock();
            Psp psp = new Psp(clock);
            Cutover cutover = new Cutover(new CheckThenSave(psp), new ClaimFirst(clock, psp, PspKeys.ONE_PER_OPERATION));
            cutover.pay("key-G", requestFor("key-G"));                         // the old handler charged; the answer was lost
            if (backfill) {
                cutover.fresh.legacy = cutover.old.saved;                       // and the new handler looks up old keys
                cutover.drainBackfillSwitch();
            } else {
                cutover.switchAtOnce();
            }
            Response retry = cutover.pay("key-G", requestFor("key-G"));
            String label = backfill ? "copy the old handler's payments, then look up old keys" : "switch; the new handler treats the key as new";
            System.out.printf("   %-55s %11d   %s%n", label, psp.charges.size(), retry);
            if (backfill) check(failures, "part 8", cutover.fresh);
        }
        System.out.println("   a retry arrives while the old handler is still charging:");
        for (boolean drain : new boolean[] {false, true}) {
            Clock clock = new Clock();
            Psp psp = new Psp(clock);
            psp.latencyMs = 200;
            Cutover cutover = new Cutover(new CheckThenSave(psp), new ClaimFirst(clock, psp, PspKeys.ONE_PER_OPERATION));
            cutover.fresh.legacy = cutover.old.saved;                           // legacy lookup on in both cases
            Thread original = Thread.ofVirtual().start(() -> cutover.pay("key-H", requestFor("key-H")));
            sleepMillis(50);
            Thread switcher = Thread.ofVirtual().start(drain ? cutover::drainBackfillSwitch : cutover::switchAtOnce);
            sleepMillis(50);
            Response retry = cutover.pay("key-H", requestFor("key-H"));
            String got = retry.toString();
            if (retry.status() == 503) {                                        // the client waits as told, then retries
                original.join();
                switcher.join();
                got += ", then " + cutover.pay("key-H", requestFor("key-H"));
            }
            original.join();
            switcher.join();
            String label = drain ? "  drain the old handler, copy its payments, then switch" : "  switch at once";
            System.out.printf("   %-55s %11d   %s%n", label, psp.charges.size(), got);
            if (drain) check(failures, "part 8", cutover.fresh);
        }

        System.out.println();
        System.out.println("Checks on the claim-first handler (parts 1-8, the correct configurations)");
        for (String name : List.of("no checkout charged more than once", "every SUCCEEDED payment has exactly one matching charge",
                                   "no charge without a SUCCEEDED payment", "ledger debits equal credits, per payment")) {
            List<String> mine = failures.stream().filter(f -> f.contains(name)).toList();
            System.out.printf("   %-58s %s%n", name, mine.isEmpty() ? "ok" : "FAILED " + mine);
        }
        if (!failures.isEmpty()) System.exit(1);
    }

    // ------------------------------------------------------------------ helpers

    static Request requestFor(String key) { return new Request("cust-" + key, 2500, "EUR"); }

    static String duration(long ms) { return ms >= HOUR ? ms / HOUR + " h" : ms / MINUTE + " min"; }

    /** Releases {@code copies} identical requests per payment at the same instant, on virtual threads. */
    static Map<String, List<Response>> burst(BiFunction<String, Request, Response> handler, int payments, int copies) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Map<String, List<Future<Response>>> futures = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int p = 0; p < payments; p++) {
                String key = "key-" + p;
                Request request = requestFor(key);
                for (int c = 0; c < copies; c++)
                    futures.computeIfAbsent(key, k -> new ArrayList<>()).add(executor.submit(() -> {
                        start.await();
                        return handler.apply(key, request);
                    }));
            }
            start.countDown();
        }
        Map<String, List<Response>> responses = new LinkedHashMap<>();
        for (var e : futures.entrySet()) {
            List<Response> list = new ArrayList<>();
            for (Future<Response> f : e.getValue()) list.add(f.get());
            responses.put(e.getKey(), list);
        }
        return responses;
    }

    static long chargedMoreThanOnce(Psp psp) {
        synchronized (psp.charges) {
            return psp.charges.stream().collect(Collectors.groupingBy(Charge::reference, Collectors.counting()))
                    .values().stream().filter(n -> n > 1).count();
        }
    }

    static String summary(Map<String, List<Response>> responses) {
        return statusCounts(responses.values().stream().flatMap(List::stream).toList());
    }

    static String statusCounts(List<Response> responses) {
        Map<String, Long> counts = new TreeMap<>();
        for (Response r : responses) {
            String label = switch (r.status()) {
                case 201 -> r.body().endsWith("(replayed)") ? "201 replayed" : "201 charged";
                case 409 -> "409 in progress";
                case 500 -> "500 duplicate key";
                default -> String.valueOf(r.status());
            };
            counts.merge(label, 1L, Long::sum);
        }
        return counts.entrySet().stream().map(e -> String.format("%,d x %s", e.getValue(), e.getKey()))
                .collect(Collectors.joining(", "));
    }

    static void tryPay(Runnable call) {
        try { call.run(); } catch (Crash e) { /* the client sees a reset connection and will retry */ }
    }

    /**
     * The invariants a payments system must keep, counted per checkout (the client's key): the old handler's
     * charges carry the key as their reference, the new handler's carry the operation's ID.
     */
    static void check(List<String> failures, String part, ClaimFirst service) {
        Map<String, Operation> byId = service.operations.values().stream().collect(Collectors.toMap(Operation::id, o -> o));
        Map<String, List<Charge>> byCheckout;
        synchronized (service.psp.charges) {
            byCheckout = service.psp.charges.stream().collect(Collectors.groupingBy(c ->
                    byId.containsKey(c.reference()) ? byId.get(c.reference()).key() : c.reference()));
        }
        byCheckout.forEach((key, charges) -> {
            if (charges.size() > 1) failures.add(part + ": no checkout charged more than once (" + key + ")");
            Operation op = service.operations.get(key);
            if (op == null || op.state() != State.SUCCEEDED)
                failures.add(part + ": no charge without a SUCCEEDED payment (" + key + ")");
        });
        for (Operation op : service.operations.values()) {
            if (op.state() != State.SUCCEEDED) continue;
            List<Charge> charges = byCheckout.getOrDefault(op.key(), List.of());
            if (charges.size() != 1 || !charges.get(0).id().equals(op.chargeId()) || charges.get(0).amount() != op.amount())
                failures.add(part + ": every SUCCEEDED payment has exactly one matching charge (" + op.id() + ")");
        }
        Map<String, long[]> sums = new HashMap<>();
        synchronized (service.ledger) {
            for (LedgerEntry e : service.ledger) {
                long[] s = sums.computeIfAbsent(e.operationId(), k -> new long[2]);
                s[0] += e.debit();
                s[1] += e.credit();
            }
        }
        for (Operation op : service.operations.values()) {
            long[] s = sums.getOrDefault(op.id(), new long[2]);
            long expected = op.state() == State.SUCCEEDED ? op.amount() : 0;
            if (s[0] != s[1] || s[0] != expected)
                failures.add(part + ": ledger debits equal credits, per payment (" + op.id() + ")");
        }
    }

    static void sleepMillis(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
