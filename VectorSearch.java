import java.util.*;

/**
 * Chapter 35 lab: what a vector index actually does, measured.
 *
 *   java -Xmx2g VectorSearch.java
 *
 * 50,000 synthetic "embeddings" (128 dimensions, unit length, drawn from 200 clusters, standing in for
 * real embeddings, which are also clustered), 500 queries from the same distribution, top-10 by cosine.
 *   1. exact brute force: the ground truth, and its cost
 *   2. an HNSW index built here (M=16, efConstruction=100): recall@10 and cost as efSearch grows
 *   3. quantization: int8 and 1-bit vectors, with and without re-scoring the candidates in float32
 *   4. filtered search: post-filtering an ANN result vs searching only the rows that match
 * Synthetic data means the absolute numbers are illustrative; the shapes of the trade-offs are the point.
 */
public class VectorSearch {
    static final int N = 50_000, Q = 500, D = 128, K = 10, CLUSTERS = 200, TENANTS = 100;

    public static void main(String[] args) {
        Random rnd = new Random(42);
        float[][] centers = new float[CLUSTERS][];
        for (int c = 0; c < CLUSTERS; c++) centers[c] = normalize(gaussian(rnd, D, 1.0));
        float[] data = new float[N * D], queries = new float[Q * D];
        for (int i = 0; i < N; i++) System.arraycopy(sample(rnd, centers), 0, data, i * D, D);
        for (int q = 0; q < Q; q++) System.arraycopy(sample(rnd, centers), 0, queries, q * D, D);
        int[] tenant = new int[N];
        for (int i = 0; i < N; i++) tenant[i] = rnd.nextInt(TENANTS);
        System.out.printf("%,d vectors x %d dimensions (%,d KB as float32), %,d queries, top-%d by cosine%n%n",
                N, D, (long) N * D * 4 / 1024, Q, K);

        // ---------------------------------------------------------------- 1. exact
        int[][] truth = new int[Q][];
        long t0 = System.nanoTime();
        for (int q = 0; q < Q; q++) truth[q] = exactTopK(data, queries, q, K, null);
        double exactUs = (System.nanoTime() - t0) / 1e3 / Q;
        t0 = System.nanoTime();
        for (int q = 0; q < Q; q++) truth[q] = exactTopK(data, queries, q, K, null);      // timed again, warm
        exactUs = Math.min(exactUs, (System.nanoTime() - t0) / 1e3 / Q);
        System.out.println("1. Exact search (brute force)");
        System.out.printf("   recall@10 = 1.000 by definition; %,.0f us per query; %,d similarity computations per query%n%n", exactUs, N);

        // ---------------------------------------------------------------- 2. HNSW
        System.out.println("2. HNSW (M=16, efConstruction=100)");
        t0 = System.nanoTime();
        Hnsw index = new Hnsw(data, N, D, 16, 100, 7);
        for (int i = 0; i < N; i++) index.insert(i);
        double buildS = (System.nanoTime() - t0) / 1e9;
        System.out.printf("   built in %.1f s; %s; graph links use %,d KB%n", buildS, index.levelsSummary(), index.linkBytes() / 1024);
        System.out.println("   efSearch  recall@10  us/query  similarity computations per query (share of the data)");
        for (int ef : new int[]{10, 20, 40, 80, 160, 320}) {
            for (int q = 0; q < Q; q++) index.search(queries, q * D, K, ef);                   // warm-up pass
            index.distanceCalls = 0;
            double recall = 0;
            t0 = System.nanoTime();
            for (int q = 0; q < Q; q++) recall += recall(index.search(queries, q * D, K, ef), truth[q]);
            double us = (System.nanoTime() - t0) / 1e3 / Q;
            double calls = (double) index.distanceCalls / Q;
            System.out.printf("   %8d  %9.3f  %8.0f  %,8.0f (%.1f%%)%n", ef, recall / Q, us, calls, 100 * calls / N);
        }
        System.out.println();

        // ---------------------------------------------------------------- 3. quantization
        System.out.println("3. Quantization (brute force over compressed vectors, then optional re-scoring in float32)");
        byte[] q8 = new byte[N * D]; float[] scale = new float[N];
        long[] bits = new long[N * (D / 64)];
        for (int i = 0; i < N; i++) {
            float max = 1e-9f;
            for (int k = 0; k < D; k++) max = Math.max(max, Math.abs(data[i * D + k]));
            scale[i] = max / 127f;
            for (int k = 0; k < D; k++) {
                q8[i * D + k] = (byte) Math.round(data[i * D + k] / scale[i]);
                if (data[i * D + k] > 0) bits[i * (D / 64) + k / 64] |= 1L << (k % 64);
            }
        }
        System.out.println("   format          bytes/vector  re-score top  recall@10");
        System.out.printf("   float32         %12d  %12s  %9.3f%n", D * 4, "-", 1.0);
        for (int rescore : new int[]{0, 50}) {
            double recall = 0;
            for (int q = 0; q < Q; q++) recall += recall(int8TopK(data, q8, scale, queries, q, rescore == 0 ? K : rescore, rescore), truth[q]);
            System.out.printf("   int8            %12d  %12s  %9.3f%n", D + 4, rescore == 0 ? "-" : String.valueOf(rescore), recall / Q);
        }
        for (int rescore : new int[]{0, 100, 500}) {
            double recall = 0;
            for (int q = 0; q < Q; q++) recall += recall(binaryTopK(data, bits, queries, q, rescore == 0 ? K : rescore, rescore), truth[q]);
            System.out.printf("   1-bit (sign)    %12d  %12s  %9.3f%n", D / 8, rescore == 0 ? "-" : String.valueOf(rescore), recall / Q);
        }
        System.out.println();

        // ---------------------------------------------------------------- 4. filtered search
        System.out.println("4. Filtered search: each vector belongs to one of 100 tenants; a query wants its tenant's top-10");
        int[][] byTenant = new int[TENANTS][];
        int[] sizes = new int[TENANTS];
        for (int t : tenant) sizes[t]++;
        for (int t = 0; t < TENANTS; t++) byTenant[t] = new int[sizes[t]];
        int[] fill = new int[TENANTS];
        for (int i = 0; i < N; i++) byTenant[tenant[i]][fill[tenant[i]]++] = i;
        int[][] filteredTruth = new int[Q][];
        for (int q = 0; q < Q; q++) filteredTruth[q] = exactTopK(data, queries, q, K, byTenant[q % TENANTS]);
        System.out.println("   strategy                                   results returned  recall@10  us/query");
        for (int ef : new int[]{40, 400, 2_000}) {
            double found = 0, recall = 0;
            for (int q = 0; q < Q; q++) index.search(queries, q * D, ef, ef);
            t0 = System.nanoTime();
            for (int q = 0; q < Q; q++) {
                int want = q % TENANTS;
                int[] hits = Arrays.stream(index.search(queries, q * D, ef, ef)).filter(i -> tenant[i] == want).limit(K).toArray();
                found += hits.length;
                recall += recall(hits, filteredTruth[q]);
            }
            double us = (System.nanoTime() - t0) / 1e3 / Q;
            System.out.printf("   HNSW top-%-5d then filter by tenant        %13.1f  %9.3f  %8.0f%n", ef, found / Q, recall / Q, us);
        }
        for (int q = 0; q < Q; q++) exactTopK(data, queries, q, K, byTenant[q % TENANTS]);
        t0 = System.nanoTime();
        double recall = 0;
        for (int q = 0; q < Q; q++) recall += recall(exactTopK(data, queries, q, K, byTenant[q % TENANTS]), filteredTruth[q]);
        System.out.printf("   exact scan of the tenant's ~%d rows only   %13.1f  %9.3f  %8.0f%n", N / TENANTS, (double) K, recall / Q,
                (System.nanoTime() - t0) / 1e3 / Q);
    }

    // ---------------------------------------------------------------- data
    static float[] gaussian(Random rnd, int d, double sigma) {
        float[] v = new float[d];
        for (int k = 0; k < d; k++) v[k] = (float) (rnd.nextGaussian() * sigma);
        return v;
    }

    static float[] normalize(float[] v) {
        double norm = 0;
        for (float x : v) norm += x * x;
        float inv = (float) (1 / Math.sqrt(norm));
        for (int k = 0; k < v.length; k++) v[k] *= inv;
        return v;
    }

    /** A cluster centre plus noise, normalized: cosine similarity is then just a dot product. */
    static float[] sample(Random rnd, float[][] centers) {
        float[] c = centers[rnd.nextInt(centers.length)], noise = gaussian(rnd, D, 0.07), v = new float[D];
        for (int k = 0; k < D; k++) v[k] = c[k] + noise[k];
        return normalize(v);
    }

    static float dot(float[] a, int aOff, float[] b, int bOff) {
        float s = 0;
        for (int k = 0; k < D; k++) s += a[aOff + k] * b[bOff + k];
        return s;
    }

    static double recall(int[] found, int[] truth) {
        Set<Integer> t = new HashSet<>();
        for (int i : truth) t.add(i);
        int hit = 0;
        for (int i : found) if (t.contains(i)) hit++;
        return (double) hit / truth.length;
    }

    // ---------------------------------------------------------------- exact and quantized scans
    /** Top-k by dot product over all rows, or only over `subset` when it isn't null. */
    static int[] exactTopK(float[] data, float[] queries, int q, int k, int[] subset) {
        TopK top = new TopK(k);
        if (subset == null) for (int i = 0; i < N; i++) top.offer(dot(queries, q * D, data, i * D), i);
        else for (int i : subset) top.offer(dot(queries, q * D, data, i * D), i);
        return top.ids();
    }

    static int[] rescore(float[] data, float[] queries, int q, int[] candidates) {
        TopK top = new TopK(K);
        for (int i : candidates) top.offer(dot(queries, q * D, data, i * D), i);
        return top.ids();
    }

    static int[] int8TopK(float[] data, byte[] q8, float[] scale, float[] queries, int q, int k, int rescore) {
        float qmax = 1e-9f;
        for (int j = 0; j < D; j++) qmax = Math.max(qmax, Math.abs(queries[q * D + j]));
        byte[] qq = new byte[D];
        for (int j = 0; j < D; j++) qq[j] = (byte) Math.round(queries[q * D + j] / (qmax / 127f));
        TopK top = new TopK(k);
        for (int i = 0; i < N; i++) {
            int s = 0;
            for (int j = 0; j < D; j++) s += qq[j] * q8[i * D + j];
            top.offer(s * scale[i], i);                                          // the query's scale is the same for every row
        }
        return rescore == 0 ? top.ids() : rescore(data, queries, q, top.ids());
    }

    static int[] binaryTopK(float[] data, long[] bits, float[] queries, int q, int k, int rescore) {
        int words = D / 64;
        long[] qb = new long[words];
        for (int j = 0; j < D; j++) if (queries[q * D + j] > 0) qb[j / 64] |= 1L << (j % 64);
        TopK top = new TopK(k);
        for (int i = 0; i < N; i++) {
            int hamming = 0;
            for (int w = 0; w < words; w++) hamming += Long.bitCount(qb[w] ^ bits[i * words + w]);
            top.offer(-hamming, i);                                              // fewer differing bits = more similar
        }
        return rescore == 0 ? top.ids() : rescore(data, queries, q, top.ids());
    }

    /** Keeps the k highest scores seen (a min-heap of size k). */
    static final class TopK {
        final int k; final PriorityQueue<long[]> heap;
        TopK(int k) { this.k = k; this.heap = new PriorityQueue<>(k + 1, Comparator.comparingDouble(e -> Double.longBitsToDouble(e[0]))); }
        void offer(double score, int id) {
            if (heap.size() < k) heap.add(new long[]{Double.doubleToLongBits(score), id});
            else if (score > Double.longBitsToDouble(heap.peek()[0])) { heap.poll(); heap.add(new long[]{Double.doubleToLongBits(score), id}); }
        }
        int[] ids() {
            List<long[]> l = new ArrayList<>(heap);
            l.sort((a, b) -> Double.compare(Double.longBitsToDouble(b[0]), Double.longBitsToDouble(a[0])));
            return l.stream().mapToInt(e -> (int) e[1]).toArray();
        }
    }

    // ---------------------------------------------------------------- HNSW
    /**
     * Hierarchical Navigable Small World graph (Malkov & Yashunin), the index behind pgvector's hnsw,
     * Lucene, Redis, Qdrant and most vector databases. Distance = 1 - cosine similarity.
     * Single-threaded and simplified (no deletes), but it uses the paper's neighbour-selection heuristic.
     */
    static final class Hnsw {
        final float[] data; final int d, m, m0, efConstruction; final double levelFactor; final Random random;
        final int[] level; final int[][] links0; final int[] count0; final int[][][] upper; final int[][] upperCount;
        final int[] visited; int epoch;
        int entry = -1, maxLevel = -1;
        long distanceCalls;

        Hnsw(float[] data, int n, int d, int m, int efConstruction, long seed) {
            this.data = data; this.d = d; this.m = m; this.m0 = 2 * m; this.efConstruction = efConstruction;
            this.levelFactor = 1 / Math.log(m); this.random = new Random(seed);
            level = new int[n]; links0 = new int[n][m0 + 1]; count0 = new int[n];
            upper = new int[n][][]; upperCount = new int[n][]; visited = new int[n];
        }

        float distance(float[] q, int qOff, int node) { distanceCalls++; return Math.max(0f, 1 - dot(q, qOff, data, node * d)); }

        int[] links(int node, int layer) { return layer == 0 ? links0[node] : upper[node][layer - 1]; }
        int count(int node, int layer) { return layer == 0 ? count0[node] : upperCount[node][layer - 1]; }
        void setCount(int node, int layer, int c) { if (layer == 0) count0[node] = c; else upperCount[node][layer - 1] = c; }

        static long pack(float dist, int node) { return ((long) Float.floatToIntBits(dist) << 32) | node; }   // dist >= 0 keeps ordering
        static float distOf(long p) { return Float.intBitsToFloat((int) (p >>> 32)); }
        static int nodeOf(long p) { return (int) p; }

        void insert(int node) {
            int lvl = (int) (-Math.log(1 - random.nextDouble()) * levelFactor);
            level[node] = lvl;
            if (lvl > 0) { upper[node] = new int[lvl][m + 1]; upperCount[node] = new int[lvl]; }
            if (entry < 0) { entry = node; maxLevel = lvl; return; }
            int ep = entry;
            for (int layer = maxLevel; layer > lvl; layer--) ep = nodeOf(searchLayer(data, node * d, new int[]{ep}, 1, layer)[0]);
            int[] eps = {ep};
            for (int layer = Math.min(lvl, maxLevel); layer >= 0; layer--) {
                long[] candidates = searchLayer(data, node * d, eps, efConstruction, layer);
                int[] chosen = selectNeighbours(candidates, m);
                System.arraycopy(chosen, 0, links(node, layer), 0, chosen.length);
                setCount(node, layer, chosen.length);
                for (int other : chosen) connect(other, node, layer);
                eps = Arrays.stream(candidates).mapToInt(Hnsw::nodeOf).toArray();
            }
            if (lvl > maxLevel) { entry = node; maxLevel = lvl; }
        }

        /** Adds `node` to `other`'s list; if that overflows, re-selects `other`'s neighbours with the heuristic. */
        void connect(int other, int node, int layer) {
            int cap = layer == 0 ? m0 : m, c = count(other, layer);
            int[] l = links(other, layer);
            if (c < cap) { l[c] = node; setCount(other, layer, c + 1); return; }
            long[] all = new long[c + 1];
            for (int i = 0; i < c; i++) all[i] = pack(distance(data, other * d, l[i]), l[i]);
            all[c] = pack(distance(data, other * d, node), node);
            Arrays.sort(all);
            int[] keep = selectNeighbours(all, cap);
            System.arraycopy(keep, 0, l, 0, keep.length);
            setCount(other, layer, keep.length);
        }

        /** The paper's heuristic: take a candidate only if it's closer to the base than to any neighbour already taken. */
        int[] selectNeighbours(long[] sortedCandidates, int max) {
            int[] out = new int[max]; int n = 0;
            for (long cand : sortedCandidates) {
                if (n == max) break;
                int c = nodeOf(cand); float dc = distOf(cand);
                boolean keep = true;
                for (int i = 0; i < n && keep; i++) if (distance(data, c * d, out[i]) < dc) keep = false;
                if (keep) out[n++] = c;
            }
            return Arrays.copyOf(out, n);
        }

        /** Best-first search on one layer, keeping the `ef` closest nodes found. Returns them sorted, closest first. */
        long[] searchLayer(float[] q, int qOff, int[] entryPoints, int ef, int layer) {
            epoch++;
            LongHeap candidates = new LongHeap(), results = new LongHeap();               // results holds negated values: a max-heap
            for (int ep : entryPoints) {
                if (visited[ep] == epoch) continue;
                visited[ep] = epoch;
                long p = pack(distance(q, qOff, ep), ep);
                candidates.push(p); results.push(-p);
                if (results.size() > ef) results.pop();
            }
            while (candidates.size() > 0) {
                long c = candidates.pop();
                if (results.size() >= ef && distOf(c) > distOf(-results.peek())) break;
                int node = nodeOf(c), n = count(node, layer);
                int[] l = links(node, layer);
                for (int i = 0; i < n; i++) {
                    int e = l[i];
                    if (visited[e] == epoch) continue;
                    visited[e] = epoch;
                    float de = distance(q, qOff, e);
                    if (results.size() < ef || de < distOf(-results.peek())) {
                        long p = pack(de, e);
                        candidates.push(p); results.push(-p);
                        if (results.size() > ef) results.pop();
                    }
                }
            }
            long[] out = new long[results.size()];
            for (int i = out.length - 1; i >= 0; i--) out[i] = -results.pop();
            return out;
        }

        int[] search(float[] q, int qOff, int k, int ef) {
            int ep = entry;
            for (int layer = maxLevel; layer > 0; layer--) ep = nodeOf(searchLayer(q, qOff, new int[]{ep}, 1, layer)[0]);
            long[] found = searchLayer(q, qOff, new int[]{ep}, Math.max(ef, k), 0);
            int[] out = new int[Math.min(k, found.length)];
            for (int i = 0; i < out.length; i++) out[i] = nodeOf(found[i]);
            return out;
        }

        String levelsSummary() {
            int[] perLevel = new int[maxLevel + 1];
            for (int l : level) for (int i = 0; i <= l; i++) perLevel[i]++;
            long links = 0;
            for (int c : count0) links += c;
            StringBuilder sb = new StringBuilder(String.format("%d layers, nodes per layer %s", maxLevel + 1, Arrays.toString(perLevel)));
            sb.append(String.format(", %.1f links per node on layer 0", (double) links / level.length));
            return sb.toString();
        }

        long linkBytes() {
            long b = (long) level.length * (m0 + 1) * 4;
            for (int[][] u : upper) if (u != null) b += (long) u.length * (m + 1) * 4;
            return b;
        }
    }

    /** A min-heap of primitive longs. */
    static final class LongHeap {
        long[] a = new long[64]; int n;
        int size() { return n; }
        long peek() { return a[0]; }
        void push(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            int i = n++;
            while (i > 0) { int p = (i - 1) / 2; if (a[p] <= v) break; a[i] = a[p]; i = p; }
            a[i] = v;
        }
        long pop() {
            long top = a[0], v = a[--n];
            int i = 0;
            while (true) {
                int c = 2 * i + 1;
                if (c >= n) break;
                if (c + 1 < n && a[c + 1] < a[c]) c++;
                if (a[c] >= v) break;
                a[i] = a[c]; i = c;
            }
            a[i] = v;
            return top;
        }
    }
}
