package org.example;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tuning parameter ACO dan GWO (tanpa CloudSim, memakai model penjadwalan yang sama dengan Main).
 * Menguji kombinasi ukuran populasi (semut / serigala) dan jumlah iterasi pada semua skenario dan banyak seed.
 * Hasil: output/tuning_hasil_ACO.csv dan output/tuning_hasil_GWO.csv.
 * Jalankan: mvn -q compile exec:java -Dexec.mainClass=org.example.Tuning   (tambah -Dexec.args=GWO untuk satu algoritma)
 *
 * Makespan dinormalkan dengan lower bound (makespan ideal), jadi nilai 1.00 = sempurna dan nilai
 * untuk skenario berbeda bisa dirata-rata secara adil.
 */
public class Tuning {

    // Rentang uji per algoritma (populasi kelipatan 5). GWO diuji lebih luas karena hasil terbaiknya
    // ternyata ada di iterasi kecil dan populasi besar, jadi rentang awal terlalu sempit.
    static final int[] ACO_POPULATIONS = {5, 10, 15, 20, 25, 30};
    static final int[] ACO_ITERATIONS = {25, 50, 75, 100};
    static final int[] GWO_POPULATIONS = {5, 10, 15, 20, 25, 30, 35, 40, 45, 50};
    static final int[] GWO_ITERATIONS = {5, 10, 15, 20, 25, 50, 75, 100};
    static final int[] SCENARIOS = {100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
    static final int SEED_COUNT = 10; // seed 1..10

    public static void main(String[] args) throws Exception {
        // VM sama dengan Main.createVms (seed 99): urutan pemanggilan Random harus identik.
        int vmCount = 30;
        double[] mips = new double[vmCount];
        int[] pes = new int[vmCount];
        Random rand = new Random(99);
        for (int i = 0; i < vmCount; i++) {
            mips[i] = 250 + rand.nextInt(751);
            pes[i] = 1 + rand.nextInt(4);
            rand.nextInt(1537); // RAM, tidak dipakai di model tetapi menjaga urutan Random
        }

        // Argumen opsional: ACO atau GWO (default keduanya). Tiap algoritma ditulis ke file sendiri.
        List<String> algos = args.length > 0 ? List.of(args) : List.of("ACO", "GWO");
        Files.createDirectories(Paths.get("output"));
        for (String algo : algos) runAlgorithm(algo, mips, pes);
    }

    private static void runAlgorithm(String algo, double[] mips, int[] pes) throws Exception {
        int[] populations = algo.equals("ACO") ? ACO_POPULATIONS : GWO_POPULATIONS;
        int[] iterations = algo.equals("ACO") ? ACO_ITERATIONS : GWO_ITERATIONS;
        List<String> rows = new ArrayList<>();
        rows.add("Algoritma,Populasi,Iterasi,Evaluasi,N_Task,Seed,Makespan,Makespan_Norm");

        ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        for (int n : SCENARIOS) {
            MetaScheduler scheduler = new MetaScheduler(loadLengths(n), mips, pes);
            for (int pop : populations) {
                for (int iter : iterations) {
                    for (int seed = 1; seed <= SEED_COUNT; seed++) {
                        final int s = seed;
                        pool.submit(() -> {
                            MetaScheduler.Result r = algo.equals("ACO")
                                    ? scheduler.runAco(s, pop, iter)
                                    : scheduler.runGwo(s, pop, iter);
                            int evaluations = algo.equals("ACO") ? pop * iter : pop * (iter + 1);
                            String line = String.format(Locale.US, "%s,%d,%d,%d,%d,%d,%.4f,%.6f",
                                    algo, pop, iter, evaluations, n, s, r.makespan,
                                    r.makespan / scheduler.lowerBound());
                            synchronized (rows) { rows.add(line); }
                        });
                    }
                }
            }
            System.err.println(algo + ": skenario " + n + " dijadwalkan");
        }
        pool.shutdown();
        pool.awaitTermination(3, TimeUnit.HOURS);

        String file = "output/tuning_hasil_" + algo + ".csv";
        Files.write(Paths.get(file), rows);
        System.err.println("Selesai: " + file + " (" + (rows.size() - 1) + " run)");
    }

    private static double[] loadLengths(int n) throws IOException {
        String path = "/dataset/GoCJ_Dataset_" + n + ".txt";
        try (InputStream is = Tuning.class.getResourceAsStream(path);
             BufferedReader in = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return in.lines().map(String::trim).filter(l -> !l.isEmpty()).mapToDouble(Double::parseDouble).toArray();
        }
    }
}
