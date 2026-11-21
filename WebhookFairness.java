/*
 * Lab:       Webhook delivery with one dead endpoint and one bulk import: shared queue vs fair queues vs fair queues + breakers
 * Chapters:  38 (webhook delivery case study), 33 Mock 2, 22 §8 (resilience patterns)
 * Run:       java WebhookFairness.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   AWS Builders' Library, "Fairness in multi-tenant systems": https://aws.amazon.com/builders-library/fairness-in-multi-tenant-systems/
 * Output:    deterministic: a discrete-event simulation with fixed seeds, so it runs ~40 simulated minutes in seconds.
 *            Exits with status 1 if a self-check fails (every event is delivered or dead-lettered exactly once).
 */
import java.util.*;

public class WebhookFairness {

    static final int WORKERS = 64, STEADY = 20, BULK = 20, BROKEN = 21, TENANTS = 22, CAP = 16, MAX_ATTEMPTS = 10;
    static final long SECOND = 1_000, TIMEOUT = 10 * SECOND, BURST_AT = 60 * SECOND, RECOVERS_AT = 600 * SECOND,
                      ARRIVALS_END = 1_200 * SECOND;
    static final int BURST = 30_000;

    enum Strategy {
        SHARED("one shared FIFO queue"), FAIR("per-tenant queues, max 16 in flight per endpoint"),
        FAIR_BREAKER("  ... plus a circuit breaker per endpoint");
        final String label;
        Strategy(String label) { this.label = label; }
    }

    static final class Job {
        final int tenant; final long created; int attempts;
        Job(int tenant, long created) { this.tenant = tenant; this.created = created; }
    }

    enum Kind { ARRIVAL, BURST, READY, SUCCEEDED, FAILED, HALF_OPEN }
    record Event(long time, long seq, Kind kind, Job job, int tenant) {}

    enum Breaker { CLOSED, OPEN, HALF_OPEN }

    static final class Simulation {
        final Strategy strategy;
        final PriorityQueue<Event> events = new PriorityQueue<>(Comparator.comparingLong(Event::time).thenComparingLong(Event::seq));
        final ArrayDeque<Job> shared = new ArrayDeque<>();
        final List<ArrayDeque<Job>> perTenant = new ArrayList<>();
        final int[] inFlight = new int[TENANTS], consecutiveFailures = new int[TENANTS];
        final Breaker[] breaker = new Breaker[TENANTS];
        final long[] openFor = new long[TENANTS];
        final SplittableRandom[] arrivals = new SplittableRandom[TENANTS];   // same traffic for every strategy
        final SplittableRandom[] random = new SplittableRandom[TENANTS];     // latencies and jitter
        long seq, now;
        int freeWorkers = WORKERS, roundRobin;

        // measurements
        final List<Long> steadyDelays = new ArrayList<>();
        long created, delivered, deadLettered, brokenFailures, wastedMillis, bulkDrainedAt = -1, brokenBacklogClearedAt = -1;
        int bulkLeft = BURST, brokenOutageBacklog;

        Simulation(Strategy strategy) {
            this.strategy = strategy;
            for (int t = 0; t < TENANTS; t++) {
                perTenant.add(new ArrayDeque<>());
                breaker[t] = Breaker.CLOSED;
                arrivals[t] = new SplittableRandom(1_000 + t);
                random[t] = new SplittableRandom(2_000 + t);
                if (t != BULK) schedule(nextArrival(t, 0), Kind.ARRIVAL, null, t);
            }
            schedule(BURST_AT, Kind.BURST, null, BULK);
        }

        void schedule(long time, Kind kind, Job job, int tenant) { events.add(new Event(time, seq++, kind, job, tenant)); }

        long nextArrival(int tenant, long after) {                      // Poisson, 10 events per second
            return after + (long) Math.ceil(-Math.log(1 - arrivals[tenant].nextDouble()) * 100);
        }

        void run() {
            while (!events.isEmpty()) {
                Event e = events.poll();
                now = e.time();
                switch (e.kind()) {
                    case ARRIVAL -> {
                        accept(new Job(e.tenant(), now));
                        long next = nextArrival(e.tenant(), now);
                        if (next < ARRIVALS_END) schedule(next, Kind.ARRIVAL, null, e.tenant());
                    }
                    case BURST -> { for (int i = 0; i < BURST; i++) accept(new Job(BULK, now)); }
                    case READY -> enqueue(e.job());
                    case SUCCEEDED -> succeeded(e.job());
                    case FAILED -> failed(e.job());
                    case HALF_OPEN -> breaker[e.tenant()] = Breaker.HALF_OPEN;
                }
                dispatch();
            }
        }

        void accept(Job job) {
            created++;
            if (job.tenant == BROKEN && job.created < RECOVERS_AT) brokenOutageBacklog++;
            enqueue(job);
        }

        void enqueue(Job job) {
            if (strategy == Strategy.SHARED) shared.addLast(job); else perTenant.get(job.tenant).addLast(job);
        }

        void dispatch() {
            while (freeWorkers > 0) {
                Job job = strategy == Strategy.SHARED ? shared.pollFirst() : nextFair();
                if (job == null) return;
                attempt(job);
            }
        }

        Job nextFair() {
            for (int i = 0; i < TENANTS; i++) {
                int t = (roundRobin + i) % TENANTS;
                if (perTenant.get(t).isEmpty()) continue;
                boolean allowed = switch (breaker[t]) {
                    case CLOSED -> inFlight[t] < CAP;
                    case OPEN -> false;
                    case HALF_OPEN -> inFlight[t] == 0;                 // a single probe
                };
                if (!allowed) continue;
                roundRobin = t + 1;
                return perTenant.get(t).pollFirst();
            }
            return null;
        }

        void attempt(Job job) {
            freeWorkers--;
            inFlight[job.tenant]++;
            job.attempts++;
            boolean hangs = job.tenant == BROKEN && now < RECOVERS_AT;
            if (hangs) {
                brokenFailures++;
                wastedMillis += TIMEOUT;
                schedule(now + TIMEOUT, Kind.FAILED, job, job.tenant);
            } else {
                long latency = job.tenant < STEADY ? 40 + random[job.tenant].nextInt(81) : 60 + random[job.tenant].nextInt(41);
                schedule(now + latency, Kind.SUCCEEDED, job, job.tenant);
            }
        }

        void succeeded(Job job) {
            release(job.tenant);
            consecutiveFailures[job.tenant] = 0;
            breaker[job.tenant] = Breaker.CLOSED;
            delivered++;
            if (job.tenant < STEADY) steadyDelays.add(now - job.created);
            if (job.tenant == BULK && --bulkLeft == 0) bulkDrainedAt = now;
            if (job.tenant == BROKEN && job.created < RECOVERS_AT) outageEventDone();
        }

        void failed(Job job) {
            release(job.tenant);
            int t = job.tenant;
            consecutiveFailures[t]++;
            if (strategy == Strategy.FAIR_BREAKER) {
                if (breaker[t] == Breaker.HALF_OPEN) open(t, Math.min(openFor[t] * 2, 120 * SECOND));
                else if (breaker[t] == Breaker.CLOSED && consecutiveFailures[t] >= 5) open(t, 30 * SECOND);
            }
            if (job.attempts >= MAX_ATTEMPTS) {
                deadLettered++;
                if (t == BROKEN && job.created < RECOVERS_AT) outageEventDone();
                return;
            }
            long backoff = Math.min(SECOND << (job.attempts - 1), 300 * SECOND);
            backoff = backoff * (80 + random[t].nextInt(41)) / 100;     // +-20% jitter
            schedule(now + backoff, Kind.READY, job, t);
        }

        void open(int tenant, long duration) {
            breaker[tenant] = Breaker.OPEN;
            openFor[tenant] = duration;
            schedule(now + duration, Kind.HALF_OPEN, null, tenant);
        }

        void release(int tenant) { freeWorkers++; inFlight[tenant]--; }

        void outageEventDone() {
            if (--brokenOutageBacklog == 0 && now >= RECOVERS_AT) brokenBacklogClearedAt = now;
        }
    }

    public static void main(String[] args) {
        System.out.println("Simulated webhook delivery: 64 workers, a 10 s timeout, retries backing off from 1 s (doubling, +-20% jitter,");
        System.out.println("at most 5 min apart), 10 attempts, then the dead-letter queue. 20 minutes of traffic:");
        System.out.println("  20 steady tenants: 10 events/s each; their endpoints answer in 40-120 ms");
        System.out.println("  1 bulk tenant: 30,000 events at once, 1 minute in (a backfill); its endpoint answers in 60-100 ms");
        System.out.println("  1 broken tenant: 10 events/s; its endpoint hangs until our timeout for the first 10 minutes, then recovers");
        System.out.println();

        List<Simulation> runs = new ArrayList<>();
        for (Strategy s : Strategy.values()) {
            Simulation sim = new Simulation(s);
            sim.run();
            runs.add(sim);
        }
        boolean ok = true;

        System.out.println("1. Delivery delay for the 20 steady tenants (event created -> 2xx received)");
        System.out.printf("   %-50s %9s %9s %9s %14s%n", "strategy", "p50", "p99", "max", "later than 5 s");
        for (Simulation sim : runs) {
            long[] d = sim.steadyDelays.stream().mapToLong(Long::longValue).sorted().toArray();
            long late = Arrays.stream(d).filter(x -> x > 5 * SECOND).count();
            System.out.printf("   %-50s %9s %9s %9s %13.1f%%%n", sim.strategy.label, time(d[d.length / 2]),
                              time(d[(int) Math.ceil(0.99 * d.length) - 1]), time(d[d.length - 1]), 100.0 * late / d.length);
        }

        System.out.println();
        System.out.println("2. The bulk tenant's 30,000 events, and the broken tenant's endpoint");
        System.out.printf("   %-50s %12s %10s %12s %15s%n", "strategy", "bulk drained", "timed-out", "worker-hours", "outage backlog");
        System.out.printf("   %-50s %12s %10s %12s %15s%n", "", "after", "attempts", "on timeouts", "cleared after");
        for (Simulation sim : runs) {
            System.out.printf("   %-50s %12s %,10d %12.1f %15s%n", sim.strategy.label, time(sim.bulkDrainedAt - BURST_AT),
                              sim.brokenFailures, sim.wastedMillis / 3_600_000.0,
                              sim.brokenBacklogClearedAt < 0 ? "never" : time(sim.brokenBacklogClearedAt - RECOVERS_AT));
            if (sim.delivered + sim.deadLettered != sim.created) {
                ok = false;
                System.out.printf("   CHECK FAILED: %s created %d, delivered %d, dead-lettered %d%n",
                                  sim.strategy, sim.created, sim.delivered, sim.deadLettered);
            }
        }
        System.out.printf("%n   \"Outage backlog\" is the broken tenant's events created during the outage; its timer starts at recovery.%n");
        for (Simulation sim : runs)
            System.out.printf("   Check, %s: %,d events created, %,d delivered, %,d dead-lettered%n",
                              sim.strategy.name().toLowerCase().replace('_', ' '), sim.created, sim.delivered, sim.deadLettered);
        if (!ok) System.exit(1);
    }

    static String time(long millis) {
        if (millis < 1_000) return millis + " ms";
        if (millis < 120_000) return String.format("%.1f s", millis / 1000.0);
        return String.format("%.1f min", millis / 60_000.0);
    }
}
