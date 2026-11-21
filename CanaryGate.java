/*
 * Lab:       Canary gates: how many requests a canary must see before its p99 means anything
 * Chapters:  37 (fleet JDK upgrade: rollout gates), 22 §10 (deployment strategies)
 * Run:       java CanaryGate.java
 * Needs:     JDK 21 or newer, nothing else
 * Verified:  JDK 21.0.12 and 25.0.5 on Linux x86-64, 2026-11-20
 * Sources:   Google SRE Workbook, "Canarying Releases": https://sre.google/workbook/canarying-releases/
 * Output:    deterministic for a given JDK (fixed seeds); JDK 21 and 25 agree to within 0.2 points.
 *            Runs in about 5 seconds on two cores.
 *
 * Latencies are lognormal (median 80 ms, p99 about 407 ms). The baseline's percentiles come from a long
 * window, so they're treated as exact; the canary's come from the requests it has served. The gate blocks
 * a release when the canary's percentile is more than 5% worse than the baseline's.
 */
import java.util.*;
import java.util.stream.IntStream;

public class CanaryGate {

    static final double MU = Math.log(80), SIGMA = 0.7, TOLERANCE = 1.05;
    static final int[] SIZES = {200, 1_000, 5_000, 20_000, 100_000};

    enum Scenario {
        NONE("no regression"), SLOWER("everything 10% slower"), TAIL("2% of requests +400 ms");
        final String label;
        Scenario(String label) { this.label = label; }

        double sample(SplittableRandom random) {
            double x = Math.exp(MU + SIGMA * random.nextGaussian());
            return switch (this) {
                case NONE -> x;
                case SLOWER -> x * 1.10;
                case TAIL -> random.nextDouble() < 0.02 ? x + 400 : x;
            };
        }
    }

    public static void main(String[] args) {
        Map<Scenario, double[]> truth = new EnumMap<>(Scenario.class);   // {p50, p99} from 4 million requests
        for (Scenario s : Scenario.values()) {
            double[] big = draw(s, 4_000_000, new SplittableRandom(7 + s.ordinal()));
            truth.put(s, new double[] {percentile(big, 0.50), percentile(big, 0.99)});
        }
        double[] base = truth.get(Scenario.NONE);
        System.out.println("1. The releases being judged (true values, from 4 million requests each)");
        for (Scenario s : Scenario.values()) {
            double[] t = truth.get(s);
            System.out.printf("   %-24s p50 %5.1f ms (%+5.1f%%)   p99 %5.1f ms (%+5.1f%%)%n", s.label,
                              t[0], 100 * (t[0] / base[0] - 1), t[1], 100 * (t[1] / base[1] - 1));
        }

        // One cell per (scenario, canary size); each cell has its own seed, so the result doesn't depend on threads.
        record Cell(Scenario scenario, int size) {}
        List<Cell> cells = new ArrayList<>();
        for (Scenario s : Scenario.values()) for (int n : SIZES) cells.add(new Cell(s, n));
        Map<Cell, double[]> verdicts = new HashMap<>();                   // {p50 gate wrong, p99 gate wrong} as rates
        IntStream.range(0, cells.size()).parallel().forEach(i -> {
            Cell c = cells.get(i);
            int trials = c.size() <= 20_000 ? 1_000 : 200;
            SplittableRandom random = new SplittableRandom(1_000L * i + 17);
            int wrong50 = 0, wrong99 = 0;
            for (int t = 0; t < trials; t++) {
                double[] canary = draw(c.scenario(), c.size(), random);
                boolean blocked50 = percentile(canary, 0.50) > base[0] * TOLERANCE;
                boolean blocked99 = percentile(canary, 0.99) > base[1] * TOLERANCE;
                boolean shouldBlock = c.scenario() != Scenario.NONE;
                if (blocked50 != shouldBlock) wrong50++;
                if (blocked99 != shouldBlock) wrong99++;
            }
            synchronized (verdicts) { verdicts.put(c, new double[] {100.0 * wrong50 / trials, 100.0 * wrong99 / trials}); }
        });

        System.out.println();
        System.out.println("2. How often the gate is wrong, by the number of requests the canary has served");
        System.out.println(String.format("   %8s   %-25s %-25s %-25s", "canary", "false alarms:", "misses:", "misses:").stripTrailing());
        System.out.println(String.format("   %8s   %-25s %-25s %-25s", "requests", Scenario.NONE.label, Scenario.SLOWER.label, Scenario.TAIL.label).stripTrailing());
        System.out.println(String.format("   %8s   %-25s %-25s %-25s", "", "p99 gate    p50 gate", "p99 gate    p50 gate", "p99 gate    p50 gate").stripTrailing());
        for (int n : SIZES) {
            StringBuilder row = new StringBuilder(String.format("   %,8d", n));
            for (Scenario s : Scenario.values()) {
                double[] v = verdicts.get(new Cell(s, n));
                row.append(String.format("   %7.1f%%    %7.1f%%    ", v[1], v[0]));
            }
            System.out.println(row.toString().stripTrailing());
        }

        System.out.println();
        System.out.println("3. How long the canary takes to serve that many requests (it gets 5% of the traffic)");
        System.out.printf("   %16s %13s %16s %16s %17s%n", "service traffic", "canary", "1,000 requests", "20,000 requests", "100,000 requests");
        for (int perSecond : new int[] {2_000, 200, 20}) {
            double canaryRate = perSecond * 0.05;
            System.out.printf("   %,10d req/s %9s/s %16s %16s %17s%n", perSecond, trim(canaryRate),
                              duration(1_000 / canaryRate), duration(20_000 / canaryRate), duration(100_000 / canaryRate));
        }
    }

    static double[] draw(Scenario s, int n, SplittableRandom random) {
        double[] values = new double[n];
        for (int i = 0; i < n; i++) values[i] = s.sample(random);
        return values;
    }

    /** Nearest-rank percentile by quickselect (reorders the array). */
    static double percentile(double[] a, double p) {
        int k = (int) Math.ceil(p * a.length) - 1, lo = 0, hi = a.length - 1;
        while (lo < hi) {
            double pivot = a[(lo + hi) >>> 1];
            int i = lo, j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) { double tmp = a[i]; a[i] = a[j]; a[j] = tmp; i++; j--; }
            }
            if (k <= j) hi = j; else if (k >= i) lo = i; else return a[k];
        }
        return a[k];
    }

    static String trim(double v) { return v == Math.rint(v) ? String.format("%,d", (long) v) : String.format("%.1f", v); }

    static String duration(double seconds) {
        if (seconds < 120) return String.format("%.0f s", seconds);
        if (seconds < 7_200) return String.format("%.0f min", seconds / 60);
        return String.format("%.1f h", seconds / 3_600);
    }
}
