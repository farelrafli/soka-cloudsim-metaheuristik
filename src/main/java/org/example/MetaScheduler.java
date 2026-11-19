package org.example;

import java.util.Arrays;
import java.util.Random;

/**
 * ACO dan GWO untuk penjadwalan task independen ke VM (meminimalkan makespan).
 * Kelas ini murni Java (tanpa CloudSim) supaya bisa diuji terpisah.
 *
 * Model sama dengan referensi SOKA-Heuristik: tiap task butuh 1 PE, VM punya beberapa PE,
 * task pada satu VM dijalankan list-scheduling (PE yang paling cepat kosong), urutan = urutan dataset.
 * durasi(task, VM) = panjang task (MI) / MIPS VM.
 *
 * Parameter ACO/GWO mengikuti engine game (seed 42, 12 semut/serigala, 50 iterasi).
 * Catatan: PRNG berbeda dari game (java.util.Random vs Mulberry32), jadi hasil tidak identik bit-per-bit.
 */
public final class MetaScheduler {

    public static final class Result {
        public final int[] assignment;
        public final double makespan;
        public final double[] bestHistory; // best-so-far per iterasi

        Result(int[] assignment, double makespan, double[] bestHistory) {
            this.assignment = assignment;
            this.makespan = makespan;
            this.bestHistory = bestHistory;
        }
    }

    private final double[] len;   // panjang task (MI)
    private final double[] mips;  // MIPS per VM
    private final int[] offset;   // indeks awal PE tiap VM pada array flat
    private final int n, m, totalPes;
    private final double lowerBound;

    public MetaScheduler(double[] taskLengthMI, double[] vmMips, int[] vmPes) {
        this.len = taskLengthMI;
        this.mips = vmMips;
        this.n = taskLengthMI.length;
        this.m = vmMips.length;
        this.offset = new int[m + 1];
        for (int j = 0; j < m; j++) offset[j + 1] = offset[j] + vmPes[j];
        this.totalPes = offset[m];
        double sum = 0, cap = 0;
        for (double l : len) sum += l;
        for (int j = 0; j < m; j++) cap += vmMips[j] * vmPes[j];
        this.lowerBound = sum / cap; // makespan ideal bila beban terbagi sempurna
    }

    /** Makespan model untuk satu penugasan task -> VM (list scheduling pada PE terdekat kosong). */
    public double evaluate(int[] assignment) {
        double[] avail = new double[totalPes];
        double makespan = 0;
        for (int t = 0; t < n; t++) {
            int j = assignment[t];
            int best = offset[j];
            for (int p = offset[j] + 1; p < offset[j + 1]; p++) if (avail[p] < avail[best]) best = p;
            avail[best] += len[t] / mips[j];
            if (avail[best] > makespan) makespan = avail[best];
        }
        return makespan;
    }

    // ------------------------------------------------------------------ ACO

    public Result runAco(long seed, int ants, int iterations) {
        final double alpha = 1, beta = 2, rho = 0.2, tau0 = 1, tauMin = 0.01, tauMax = 10, globalBestWeight = 0.5;
        // Pada game makespan ~6 s sehingga Q=1 cukup. Di dataset GoCJ makespan ribuan detik, deposit 1/makespan
        // akan hilang tertelan evaporasi. Maka Q diskalakan ke lower bound: deposit = lowerBound / makespan (0..1].
        final double Q = lowerBound;

        Random rng = new Random(seed);
        double[][] tau = new double[n][m];
        for (double[] row : tau) Arrays.fill(row, tau0);

        int[] globalBest = null;
        double globalBestMk = Double.MAX_VALUE;
        double[] history = new double[iterations];
        double[] weights = new double[m];

        for (int it = 0; it < iterations; it++) {
            int[] iterBest = null;
            double iterBestMk = Double.MAX_VALUE;

            for (int ant = 0; ant < ants; ant++) {
                int[] a = new int[n];
                double[] avail = new double[totalPes];
                double[] minAvail = new double[m];
                for (int t = 0; t < n; t++) {
                    double max = 0;
                    for (int j = 0; j < m; j++) {
                        double completion = minAvail[j] + len[t] / mips[j];
                        double w = Math.pow(tau[t][j], alpha) * Math.pow(1.0 / (1e-9 + completion), beta);
                        weights[j] = w;
                        if (w > max) max = w;
                    }
                    double sum = 0;
                    for (int j = 0; j < m; j++) { weights[j] = max > 0 ? weights[j] / max : 1.0; sum += weights[j]; }
                    double r = rng.nextDouble() * sum, acc = 0;
                    int pick = m - 1;
                    for (int j = 0; j < m; j++) { acc += weights[j]; if (r < acc) { pick = j; break; } }
                    a[t] = pick;
                    int best = offset[pick];
                    for (int p = offset[pick] + 1; p < offset[pick + 1]; p++) if (avail[p] < avail[best]) best = p;
                    avail[best] += len[t] / mips[pick];
                    double mn = Double.MAX_VALUE;
                    for (int p = offset[pick]; p < offset[pick + 1]; p++) mn = Math.min(mn, avail[p]);
                    minAvail[pick] = mn;
                }
                double mk = evaluate(a);
                if (mk < iterBestMk) { iterBestMk = mk; iterBest = a; }
                if (mk < globalBestMk) { globalBestMk = mk; globalBest = a.clone(); }
            }

            // Evaporasi, deposit iteration-best (bobot 1) dan global-best (bobot 0.5), lalu clamp.
            for (int t = 0; t < n; t++)
                for (int j = 0; j < m; j++) tau[t][j] = Math.max(tauMin, tau[t][j] * (1 - rho));
            double dIter = Q / iterBestMk, dGlobal = Q * globalBestWeight / globalBestMk;
            for (int t = 0; t < n; t++) { tau[t][iterBest[t]] += dIter; tau[t][globalBest[t]] += dGlobal; }
            for (int t = 0; t < n; t++)
                for (int j = 0; j < m; j++) tau[t][j] = Math.max(tauMin, Math.min(tauMax, tau[t][j]));

            history[it] = globalBestMk;
        }
        return new Result(globalBest, globalBestMk, history);
    }

    // ------------------------------------------------------------------ GWO

    private int[] decode(double[] pos) {
        int[] a = new int[n];
        for (int t = 0; t < n; t++) a[t] = Math.max(0, Math.min(m - 1, (int) Math.floor(pos[t])));
        return a;
    }

    public Result runGwo(long seed, int wolves, int iterations) {
        Random rng = new Random(seed);
        double[][] pos = new double[wolves][n];
        double[] fit = new double[wolves];
        for (int w = 0; w < wolves; w++) {
            for (int t = 0; t < n; t++) pos[w][t] = rng.nextDouble() * m;
            fit[w] = evaluate(decode(pos[w]));
        }
        int[] globalBest = null;
        double globalBestMk = Double.MAX_VALUE;
        for (int w = 0; w < wolves; w++)
            if (fit[w] < globalBestMk) { globalBestMk = fit[w]; globalBest = decode(pos[w]); }
        double[] history = new double[iterations];

        for (int it = 1; it <= iterations; it++) {
            Integer[] order = new Integer[wolves];
            for (int w = 0; w < wolves; w++) order[w] = w;
            final double[] f = fit;
            Arrays.sort(order, (x, y) -> f[x] != f[y] ? Double.compare(f[x], f[y]) : Integer.compare(x, y));
            double[][] leaders = { pos[order[0]], pos[order[1]], pos[order[2]] }; // alpha, beta, delta
            double a = iterations == 1 ? 0 : 2.0 * (1 - (it - 1.0) / (iterations - 1));

            double[][] next = new double[wolves][n];
            for (int w = 0; w < wolves; w++) {
                for (int t = 0; t < n; t++) {
                    double total = 0, x = pos[w][t];
                    for (double[] leader : leaders) {
                        double r1 = rng.nextDouble(), r2 = rng.nextDouble();
                        double A = 2 * a * r1 - a, C = 2 * r2;
                        double dist = Math.abs(C * leader[t] - x);
                        total += leader[t] - A * dist;
                    }
                    next[w][t] = Math.max(0, Math.min(m - 1e-9, total / 3));
                }
            }
            pos = next;
            for (int w = 0; w < wolves; w++) {
                int[] dec = decode(pos[w]);
                fit[w] = evaluate(dec);
                if (fit[w] < globalBestMk) { globalBestMk = fit[w]; globalBest = dec; }
            }
            history[it - 1] = globalBestMk;
        }
        return new Result(globalBest, globalBestMk, history);
    }

    /** Makespan ideal (total MI / total kapasitas MIPS); dipakai untuk menormalkan hasil tuning. */
    public double lowerBound() {
        return lowerBound;
    }
}
