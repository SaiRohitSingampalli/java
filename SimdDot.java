import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;
import java.util.Arrays;
import java.util.Random;

/**
 * Chapter 35 lab: brute-force similarity over 100,000 x 384-dimensional vectors, three ways.
 *
 *   java --add-modules jdk.incubator.vector -Xmx1g SimdDot.java [vector count, default 100000]
 *
 * The Vector API is still an incubator module (JDK 16 through 27), hence the flag and a startup warning.
 * This is a coarse timing harness, not JMH: it's fine for a several-fold effect, not for small ones.
 */
public class SimdDot {
    static int N = 100_000;                                                 // pass a smaller count to fit in the CPU caches
    static final int D = 384;
    static final VectorSpecies<Float> S = FloatVector.SPECIES_PREFERRED;

    public static void main(String[] args) {
        if (args.length > 0) N = Integer.parseInt(args[0]);
        Random rnd = new Random(1);
        float[] data = new float[N * D], query = new float[D];
        for (int i = 0; i < data.length; i++) data[i] = (float) rnd.nextGaussian();
        for (int i = 0; i < D; i++) query[i] = (float) rnd.nextGaussian();
        System.out.printf("%,d vectors x %d floats (%,d MB); preferred vector species: %d lanes x 32 bits%n%n", N, D, (long) N * D * 4 >> 20, S.length());

        float[] scalar = new float[N], unrolled = new float[N], simd = new float[N];
        double a = time("scalar loop: s += x[i] * y[i]", () -> { for (int i = 0; i < N; i++) scalar[i] = dotScalar(query, data, i * D); });
        double b = time("scalar, 4 independent accumulators", () -> { for (int i = 0; i < N; i++) unrolled[i] = dotUnrolled(query, data, i * D); });
        double c = time("Vector API, FMA, " + S.length() + " lanes", () -> { for (int i = 0; i < N; i++) simd[i] = dotVector(query, data, i * D); });
        System.out.printf("%nspeed-up over the scalar loop: %.1fx (4 accumulators), %.1fx (Vector API)%n", a / b, a / c);
        System.out.printf("largest difference from the scalar result: %.2e (4 accumulators), %.2e (Vector API), on values around %.0f%n",
                maxDiff(scalar, unrolled), maxDiff(scalar, simd), Math.sqrt(D));
    }

    static float dotScalar(float[] q, float[] d, int off) {
        float s = 0;
        for (int k = 0; k < D; k++) s += q[k] * d[off + k];            // one long chain of dependent additions
        return s;
    }

    static float dotUnrolled(float[] q, float[] d, int off) {
        float s0 = 0, s1 = 0, s2 = 0, s3 = 0;                           // four chains the CPU can overlap
        for (int k = 0; k < D; k += 4) {
            s0 += q[k] * d[off + k];
            s1 += q[k + 1] * d[off + k + 1];
            s2 += q[k + 2] * d[off + k + 2];
            s3 += q[k + 3] * d[off + k + 3];
        }
        return (s0 + s1) + (s2 + s3);
    }

    static float dotVector(float[] q, float[] d, int off) {
        FloatVector acc = FloatVector.zero(S);
        int k = 0;
        for (int bound = S.loopBound(D); k < bound; k += S.length())
            acc = FloatVector.fromArray(S, q, k).fma(FloatVector.fromArray(S, d, off + k), acc);
        float s = acc.reduceLanes(VectorOperators.ADD);
        for (; k < D; k++) s += q[k] * d[off + k];                      // tail, if D isn't a multiple of the lane count
        return s;
    }

    static double maxDiff(float[] x, float[] y) {
        double m = 0;
        for (int i = 0; i < x.length; i++) m = Math.max(m, Math.abs(x[i] - y[i]));
        return m;
    }

    /** Warms up, then reports the median of seven full scans. */
    static double time(String label, Runnable scan) {
        for (int i = 0; i < 5; i++) scan.run();
        double[] ms = new double[7];
        for (int i = 0; i < ms.length; i++) { long t0 = System.nanoTime(); scan.run(); ms[i] = (System.nanoTime() - t0) / 1e6; }
        Arrays.sort(ms);
        System.out.printf("  %-40s %7.1f ms per scan of %,d vectors%n", label, ms[3], N);
        return ms[3];
    }
}
