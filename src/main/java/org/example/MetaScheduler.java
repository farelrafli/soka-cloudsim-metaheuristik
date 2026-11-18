package org.example;

import java.util.Arrays;
import java.util.Random;

/** Menghasilkan pembagian task ke VM. Tidak menjalankan simulasi CloudSim. */
public class MetaScheduler {
    public record Result(int[] assignment, double makespan, double[] bestHistory) {}

    private final double[] taskLengths;
    private final double[] vmMips;
    private final int[] vmCores;

    public MetaScheduler(double[] taskLengths, double[] vmMips, int[] vmCores) {
        if (taskLengths.length == 0 || vmMips.length == 0 || vmMips.length != vmCores.length)
            throw new IllegalArgumentException("Task/VM kosong atau ukuran MIPS dan core berbeda");
        for (double length : taskLengths)
            if (!Double.isFinite(length) || length <= 0) throw new IllegalArgumentException("Panjang task harus positif");
        for (int vm = 0; vm < vmMips.length; vm++)
            if (!Double.isFinite(vmMips[vm]) || vmMips[vm] <= 0 || vmCores[vm] <= 0)
                throw new IllegalArgumentException("MIPS dan jumlah core harus positif");
        this.taskLengths = taskLengths.clone();
        this.vmMips = vmMips.clone();
        this.vmCores = vmCores.clone();
    }

    // Satu angka untuk setiap task: assignment[task] = indeks VM yang dipilih.
    public double evaluate(int[] assignment) {
        if (assignment.length != taskLengths.length) throw new IllegalArgumentException("Jumlah assignment salah");
        double[][] finishTimes = emptyCoreTimes();
        double makespan = 0;
        for (int task = 0; task < taskLengths.length; task++) {
            int vm = assignment[task];
            if (vm < 0 || vm >= vmMips.length) throw new IllegalArgumentException("Indeks VM salah");
            int core = earliestCore(finishTimes[vm]);
            finishTimes[vm][core] += taskLengths[task] / vmMips[vm];
            makespan = Math.max(makespan, finishTimes[vm][core]);
        }
        return makespan;
    }

    // ---------------- ACO: coba jadwal, nilai jadwal, perbarui feromon. ----------------
    public Result runAco(long seed, int ants, int iterations) {
        if (ants < 1 || iterations < 1) throw new IllegalArgumentException("Semut dan iterasi minimal 1");
        Random random = new Random(seed);
        double[][] pheromone = new double[taskLengths.length][vmMips.length];
        for (double[] row : pheromone) Arrays.fill(row, 1);
        double[] history = new double[iterations];
        int[] bestAssignment = null;
        double bestMakespan = Double.MAX_VALUE;

        // Sama dengan repo teman: Q = total MI / total kapasitas MIPS seluruh core.
        double totalLength = 0, totalCapacity = 0;
        for (double length : taskLengths) totalLength += length;
        for (int vm = 0; vm < vmMips.length; vm++) totalCapacity += vmMips[vm] * vmCores[vm];
        double q = totalLength / totalCapacity;

        for (int iteration = 0; iteration < iterations; iteration++) {
            int[] iterationBest = null;
            double iterationMakespan = Double.MAX_VALUE;
            for (int ant = 0; ant < ants; ant++) {
                int[] assignment = createAntSchedule(pheromone, random);
                double makespan = evaluate(assignment);
                if (makespan < iterationMakespan) {
                    iterationBest = assignment;
                    iterationMakespan = makespan;
                }
                if (makespan < bestMakespan) {
                    bestAssignment = assignment;
                    bestMakespan = makespan;
                }
            }
            updatePheromone(pheromone, iterationBest, bestAssignment,
                    q / iterationMakespan, 0.5 * q / bestMakespan);
            history[iteration] = bestMakespan;
        }
        return new Result(bestAssignment, bestMakespan, history);
    }

    private int[] createAntSchedule(double[][] pheromone, Random random) {
        int[] assignment = new int[taskLengths.length];
        double[][] finishTimes = emptyCoreTimes();
        double[] weights = new double[vmMips.length];
        for (int task = 0; task < taskLengths.length; task++) {
            for (int vm = 0; vm < vmMips.length; vm++) {
                int core = earliestCore(finishTimes[vm]);
                double completion = finishTimes[vm][core] + taskLengths[task] / vmMips[vm];
                // alpha=1, beta=2: feromon tinggi dan waktu selesai kecil lebih disukai.
                weights[vm] = pheromone[task][vm] * Math.pow(1 / (1e-9 + completion), 2);
            }
            int vm = roulette(weights, random);
            assignment[task] = vm;
            int core = earliestCore(finishTimes[vm]);
            finishTimes[vm][core] += taskLengths[task] / vmMips[vm];
        }
        return assignment;
    }

    private static int roulette(double[] weights, Random random) {
        // Normalisasi dengan bobot terbesar, sama dengan repo teman.
        double max = Arrays.stream(weights).max().orElseThrow();
        double sum = 0;
        for (int vm = 0; vm < weights.length; vm++) {
            weights[vm] = max > 0 ? weights[vm] / max : 1;
            sum += weights[vm];
        }
        double target = random.nextDouble() * sum, cumulative = 0;
        for (int vm = 0; vm < weights.length; vm++) {
            cumulative += weights[vm];
            if (target < cumulative) return vm;
        }
        return weights.length - 1;
    }

    private static void updatePheromone(double[][] pheromone, int[] iterationBest,
                                         int[] globalBest, double iterationDeposit, double globalDeposit) {
        for (int task = 0; task < pheromone.length; task++) {
            // Penguapan 20%, lalu penguatan pilihan terbaik iterasi dan global.
            for (int vm = 0; vm < pheromone[task].length; vm++)
                pheromone[task][vm] = Math.max(0.01, pheromone[task][vm] * 0.8);
            pheromone[task][iterationBest[task]] += iterationDeposit;
            pheromone[task][globalBest[task]] += globalDeposit;
            for (int vm = 0; vm < pheromone[task].length; vm++)
                pheromone[task][vm] = Math.max(0.01, Math.min(10, pheromone[task][vm]));
        }
    }

    // ---------------- GWO: satu serigala mewakili satu kandidat jadwal. ----------------
    public Result runGwo(long seed, int wolves, int iterations) {
        if (wolves < 3 || iterations < 1) throw new IllegalArgumentException("Serigala minimal 3, iterasi minimal 1");
        Random random = new Random(seed);
        double[][] positions = new double[wolves][taskLengths.length];
        double[] fitness = new double[wolves];
        int[] bestAssignment = null;
        double bestMakespan = Double.MAX_VALUE;
        double[] history = new double[iterations];

        for (int wolf = 0; wolf < wolves; wolf++) {
            for (int task = 0; task < taskLengths.length; task++)
                positions[wolf][task] = random.nextDouble() * vmMips.length;
            fitness[wolf] = evaluate(decode(positions[wolf]));
            if (fitness[wolf] < bestMakespan) {
                bestMakespan = fitness[wolf];
                bestAssignment = decode(positions[wolf]);
            }
        }
        for (int iteration = 0; iteration < iterations; iteration++) {
            Integer[] ranking = new Integer[wolves];
            for (int wolf = 0; wolf < wolves; wolf++) ranking[wolf] = wolf;
            Arrays.sort(ranking, (x, y) -> Double.compare(fitness[x], fitness[y]));
            double[][] leaders = {positions[ranking[0]], positions[ranking[1]], positions[ranking[2]]};
            double a = iterations == 1 ? 0 : 2.0 * (1 - (double) iteration / (iterations - 1));
            double[][] next = new double[wolves][taskLengths.length];
            for (int wolf = 0; wolf < wolves; wolf++) {
                for (int task = 0; task < taskLengths.length; task++) {
                    double total = 0;
                    for (double[] leader : leaders) {
                        double r1 = random.nextDouble(), r2 = random.nextDouble();
                        double A = 2 * a * r1 - a, C = 2 * r2;
                        double distance = Math.abs(C * leader[task] - positions[wolf][task]);
                        total += leader[task] - A * distance;
                    }
                    next[wolf][task] = Math.max(0, Math.min(vmMips.length - 1e-9, total / 3));
                }
            }
            positions = next;
            for (int wolf = 0; wolf < wolves; wolf++) {
                int[] assignment = decode(positions[wolf]);
                fitness[wolf] = evaluate(assignment);
                if (fitness[wolf] < bestMakespan) {
                    bestMakespan = fitness[wolf];
                    bestAssignment = assignment;
                }
            }
            history[iteration] = bestMakespan;
        }
        return new Result(bestAssignment, bestMakespan, history);
    }

    private int[] decode(double[] position) {
        int[] assignment = new int[position.length];
        for (int task = 0; task < position.length; task++)
            assignment[task] = Math.max(0, Math.min(vmMips.length - 1, (int) Math.floor(position[task])));
        return assignment;
    }
    private double[][] emptyCoreTimes() {
        double[][] times = new double[vmCores.length][];
        for (int vm = 0; vm < vmCores.length; vm++) times[vm] = new double[vmCores[vm]];
        return times;
    }
    private static int earliestCore(double[] times) {
        int best = 0;
        for (int core = 1; core < times.length; core++) if (times[core] < times[best]) best = core;
        return best;
    }
}
