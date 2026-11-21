/*
 * Lab:       Payment idempotency, done wrong and done right
 * Chapters:  23 §7 (idempotency keys) and 36 (the payments case study)
 * Run:       java PaymentIdempotency.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   Stripe, "Idempotent requests" and "Advanced error handling": https://docs.stripe.com/api/idempotent_requests
 *            IETF draft "The Idempotency-Key HTTP Header Field": https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/
 * Output:    part 1 varies a little with thread timing; parts 2 to 5 are deterministic.
 *            Exits with status 1 if any check on the claim-first handler fails.
 *
 * A simulated payment processor (PSP) that deduplicates on its own idempotency key and forgets keys after
 * 24 hours, an operations table with a unique constraint on the client's Idempotency-Key, and a ledger.
 * Two handlers: "check, charge, then save" (the order the first version of Chapter 23 showed) and
 * "claim the key first, charge with a stable PSP key, reconcile what stays unknown".
 */
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

public class PaymentIdempotency {

    static final long SECOND = 1_000L, HOUR = 3_600_000L;
    static final long PSP_KEY_RETENTION = 24 * HOUR;   // a PSP may prune its idempotency keys after 24 hours
    static final long LEASE = 30 * SECOND;             // a PENDING operation older than this has lost its caller

    /** Simulated time for leases and key retention. The PSP's latency is real (2 ms), so races are real. */
    static final class Clock {
        private final AtomicLong millis = new AtomicLong();
        long now() { return millis.get(); }
        void advance(long ms) { millis.addAndGet(ms); }
    }

    // ------------------------------------------------------------------ the payment processor

    static final class PspTimeout extends Exception {
        PspTimeout() { super("timed out waiting for the PSP", null, false, false); }
    }

    /** The process dies at this point; whatever wasn't committed is lost. */
    static final class Crash extends RuntimeException {
        Crash() { super("process died", null, false, false); }
    }

    record Charge(String id, String reference, long amount) {}

    static final class Psp {
        private record KeyEntry(long firstSeen, CompletableFuture<Charge> result) {}

        final Clock clock;
        final List<Charge> charges = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, KeyEntry> keys = new ConcurrentHashMap<>();
        private final AtomicInteger sequence = new AtomicInteger(4000);
        final AtomicInteger loseNextResponses = new AtomicInteger();   // fault injection: charge, then time out

        Psp(Clock clock) { this.clock = clock; }

        /** Charges the card. With a key, a repeat within the retention window replays the first result. */
        Charge charge(String idempotencyKey, String reference, long amount) throws PspTimeout {
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
            sleepMillis(2);                                                    // network and processing
            Charge charge = new Charge("ch_" + sequence.incrementAndGet(), reference, amount);
            charges.add(charge);
            return charge;
        }

        Optional<Charge> findByReference(String reference) {                  // "search payments by merchant reference"
            synchronized (charges) { return charges.stream().filter(c -> c.reference().equals(reference)).findFirst(); }
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

    static final class CheckThenSave {
        final Psp psp;
        final Map<String, String[]> saved = new ConcurrentHashMap<>();         // UNIQUE (idempotency_key): {fingerprint, body}
        volatile boolean crashAfterCharge;

        CheckThenSave(Psp psp) { this.psp = psp; }

        Response pay(String key, Request request) {
            String[] previous = saved.get(key);                                // 1. look for a saved response
            if (previous != null)
                return previous[0].equals(request.fingerprint()) ? new Response(201, previous[1] + " (replayed)")
                                                                 : new Response(422, "idempotency_key_reused");
            Charge charge;
            try {
                charge = psp.charge(null, key, request.amount());              // 2. charge the card
            } catch (PspTimeout e) {
                return new Response(504, "gateway timeout");
            }
            if (crashAfterCharge) { crashAfterCharge = false; throw new Crash(); }
            if (saved.putIfAbsent(key, new String[] {request.fingerprint(), "charged " + charge.id()}) != null)
                return new Response(500, "duplicate key value violates unique constraint");   // 3. save: too late
            return new Response(201, "charged " + charge.id());
        }
    }

    // ------------------------------------------------------------------ version 2: claim the key first

    enum State { PENDING, SUCCEEDED, UNKNOWN, NOT_CHARGED }

    record Operation(String id, String key, String fingerprint, long amount, State state, String chargeId, long createdAt) {
        Operation with(State newState, String newChargeId) {
            return new Operation(id, key, fingerprint, amount, newState, newChargeId, createdAt);
        }
    }

    record LedgerEntry(String operationId, String account, long debit, long credit) {}

    enum PspKeys { ONE_PER_OPERATION, NEW_PER_ATTEMPT }

    static final class ClaimFirst {
        final Clock clock;
        final Psp psp;
        final PspKeys pspKeys;
        final Map<String, Operation> operations = new ConcurrentHashMap<>();   // UNIQUE (merchant_id, idempotency_key)
        final List<LedgerEntry> ledger = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger ids = new AtomicInteger(16);
        volatile boolean crashAfterCharge;

        ClaimFirst(Clock clock, Psp psp, PspKeys pspKeys) { this.clock = clock; this.psp = psp; this.pspKeys = pspKeys; }

        Response pay(String key, Request request) {
            Operation fresh = new Operation("op-" + ids.incrementAndGet(), key, request.fingerprint(),
                                            request.amount(), State.PENDING, null, clock.now());
            Operation existing = operations.putIfAbsent(key, fresh);           // INSERT ... ON CONFLICT DO NOTHING, commit
            if (existing != null) return answer(existing, request);
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

        /** Calls the PSP for a claimed operation and records the outcome. Requests and the reconciler both use it. */
        Response drive(Operation op) {
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
                        ledger.add(new LedgerEntry(done.id(), "psp:clearing", done.amount(), 0));
                        ledger.add(new LedgerEntry(done.id(), "merchant:7:payable", 0, done.amount()));
                        return new Response(201, "charged " + chargeId);
                    }
                    return new Response(402, "payment_failed: not charged");
                }
            }
        }

        /** Runs every few seconds. Settles UNKNOWN outcomes and PENDING operations whose caller died. */
        void reconcile(boolean lookUpAfterKeyRetention) {
            for (Operation op : List.copyOf(operations.values())) {
                boolean stuck = op.state() == State.UNKNOWN || (op.state() == State.PENDING && clock.now() - op.createdAt() > LEASE);
                if (!stuck) continue;
                boolean keyStillKnown = clock.now() - op.createdAt() < PSP_KEY_RETENTION - HOUR;   // an hour's margin
                if (keyStillKnown || !lookUpAfterKeyRetention) {
                    drive(op);                                                  // re-send with the SAME PSP key
                } else {
                    Optional<Charge> charge = psp.findByReference(op.id());     // never re-send once the key may be gone
                    record(op.key(), charge.isPresent() ? State.SUCCEEDED : State.NOT_CHARGED, charge.map(Charge::id).orElse(null));
                }
            }
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
        System.out.printf("   %-40s %11s%n", "PSP key on the retry", "PSP charges");
        for (PspKeys strategy : List.of(PspKeys.NEW_PER_ATTEMPT, PspKeys.ONE_PER_OPERATION)) {
            Clock clock = new Clock();
            Psp psp = new Psp(clock);
            ClaimFirst service = new ClaimFirst(clock, psp, strategy);
            psp.loseNextResponses.set(1);
            Response response = service.pay("key-B", requestFor("key-B"));
            String label = strategy == PspKeys.NEW_PER_ATTEMPT ? "a new key per attempt (op-17-attempt-2)" : "the operation's key again (op-17)";
            System.out.printf("   %-40s %11d   client got %s%n", label, psp.charges.size(), response);
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
        System.out.println("Checks on the claim-first handler (parts 1-5, the correct configurations)");
        for (String name : List.of("no payment charged more than once", "every SUCCEEDED payment has exactly one matching charge",
                                   "no charge without a SUCCEEDED payment", "ledger debits equal credits, per payment")) {
            List<String> mine = failures.stream().filter(f -> f.contains(name)).toList();
            System.out.printf("   %-58s %s%n", name, mine.isEmpty() ? "ok" : "FAILED " + mine);
        }
        if (!failures.isEmpty()) System.exit(1);
    }

    // ------------------------------------------------------------------ helpers

    static Request requestFor(String key) { return new Request("cust-" + key, 2500, "EUR"); }

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

    /** The invariants a payments system must keep. */
    static void check(List<String> failures, String part, ClaimFirst service) {
        Map<String, List<Charge>> chargesByOperation;
        synchronized (service.psp.charges) {
            chargesByOperation = service.psp.charges.stream().collect(Collectors.groupingBy(Charge::reference));
        }
        Map<String, Operation> byId = service.operations.values().stream().collect(Collectors.toMap(Operation::id, o -> o));
        chargesByOperation.forEach((operationId, charges) -> {
            if (charges.size() > 1) failures.add(part + ": no payment charged more than once (" + operationId + ")");
            Operation op = byId.get(operationId);
            if (op == null || op.state() != State.SUCCEEDED)
                failures.add(part + ": no charge without a SUCCEEDED payment (" + operationId + ")");
        });
        for (Operation op : byId.values()) {
            if (op.state() != State.SUCCEEDED) continue;
            List<Charge> charges = chargesByOperation.getOrDefault(op.id(), List.of());
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
        for (Operation op : byId.values()) {
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
