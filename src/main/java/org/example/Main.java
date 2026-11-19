package org.example;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.*;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.*;
import org.cloudsimplus.hosts.*;
import org.cloudsimplus.resources.*;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.util.Log;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.vms.*;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class Main {
    private static final int DC_COUNT = 3, HOSTS_PER_DC = 5, VMS_PER_DC = 10;
    private static final long HOST_SEED = 42, VM_SEED = 99;
    private static final double COST_PER_MIPS_SECOND = 0.00001;

    // Daftar variasi parameter dinamis:
    // Population: kelipatan 5 (misal: 5, 10, 15, 20)
    // Iterations: kelipatan 20 (misal: 20, 40, 60, 80, 100)
    // Seed: daftar seed eksperimen untuk uji konsistensi/reproduksibilitas
    private static final int[] DEFAULT_POPULATIONS = { 5, 10, 15, 20 };
    private static final int[] DEFAULT_ITERATIONS = { 20, 40, 60, 80, 100 };
    private static final long[] DEFAULT_SEEDS = { 42L, 123L, 999L };

    private static boolean mappingPrinted;

    // Duplikasi keluaran ke terminal dan file log.
    public static void main(String[] args) throws IOException {
        disableCloudSimLogs();
        Files.createDirectories(Path.of("output"));
        PrintStream terminal = System.out, terminalError = System.err;
        try (PrintStream file = new PrintStream("output/output_terminal.txt", StandardCharsets.UTF_8);
                PrintStream output = new PrintStream(new TeeOutputStream(terminal, file), true, StandardCharsets.UTF_8);
                PrintStream error = new PrintStream(new TeeOutputStream(terminalError, file), true,
                        StandardCharsets.UTF_8)) {
            System.setOut(output);
            System.setErr(error);
            run(args);
        } finally {
            System.setOut(terminal);
            System.setErr(terminalError);
        }
    }

    // Matikan seluruh log internal CloudSim Plus dan Logback/SLF4J.
    private static void disableCloudSimLogs() {
        Log.setLevel(Level.OFF);
        if (LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.OFF);
            context.getLogger("org.cloudsimplus").setLevel(Level.OFF);
        }
    }

    /**
     * Format argumen CLI (semua opsional):
     * args[0] : task count ("all" atau 100..1000 kelipatan 100), default = "all"
     * args[1] : daftar populasi kelipatan 5 dipisah koma (misal: "5,10,15,20"),
     * default = DEFAULT_POPULATIONS
     * args[2] : daftar iterasi kelipatan 20 dipisah koma (misal:
     * "20,40,60,80,100"), default = DEFAULT_ITERATIONS
     * args[3] : daftar seed dipisah koma (misal: "42,123,999"), default =
     * DEFAULT_SEEDS
     */
    private static void run(String[] args) throws IOException {
        mappingPrinted = false;
        disableCloudSimLogs();
        if (args.length > 4) {
            throw new IllegalArgumentException(
                    "Gunakan: [all|100..1000] [pop1,pop2,... (kelipatan 5)] [iter1,iter2,... (kelipatan 20)] [seed1,seed2,...]");
        }

        int selectedTask = (args.length == 0 || args[0].equalsIgnoreCase("all")) ? 0 : Integer.parseInt(args[0]);
        if (selectedTask != 0 && (selectedTask < 100 || selectedTask > 1000 || selectedTask % 100 != 0)) {
            throw new IllegalArgumentException("Jumlah task harus 100..1000 kelipatan 100");
        }

        int[] populations = args.length >= 2 ? parseIntArray(args[1], "Population") : DEFAULT_POPULATIONS;
        for (int pop : populations) {
            if (pop < 5 || pop % 5 != 0) {
                throw new IllegalArgumentException(
                        "Parameter population harus kelipatan 5 (minimal 5), ditemukan: " + pop);
            }
        }

        int[] iterationsList = args.length >= 3 ? parseIntArray(args[2], "Iterations") : DEFAULT_ITERATIONS;
        for (int iter : iterationsList) {
            if (iter < 20 || iter % 20 != 0) {
                throw new IllegalArgumentException(
                        "Parameter iteration harus kelipatan 20 (minimal 20), ditemukan: " + iter);
            }
        }

        long[] seeds = args.length >= 4 ? parseLongArray(args[3], "Seed") : DEFAULT_SEEDS;

        Files.createDirectories(Path.of("output"));
        List<String> rows = new ArrayList<>();
        rows.add(
                "Algoritma,Seed,Population,Iterations,N_Task,Prediksi_Makespan,Makespan_Aktual,Gap_Persen,Throughput,Utilization_Persen,Imbalance,Total_Cost");

        System.out.printf("CloudSim: 3 DC, 15 host, 30 VM | Populations=%s | Iterations=%s | Seeds=%s%n",
                Arrays.toString(populations), Arrays.toString(iterationsList), Arrays.toString(seeds));

        for (String algorithm : List.of("ACO", "GWO")) {
            for (long seed : seeds) {
                for (int population : populations) {
                    for (int iterations : iterationsList) {
                        for (int count = 100; count <= 1000; count += 100) {
                            if (selectedTask == 0 || selectedTask == count) {
                                rows.add(runScenario(algorithm, seed, population, iterations, count));
                            }
                        }
                    }
                }
            }
        }

        Files.write(Path.of("output/hasil_metrik_perbandingan.csv"), rows, StandardCharsets.UTF_8);
        System.out.println("Hasil tersimpan di output/hasil_metrik_perbandingan.csv");
    }

    private static int[] parseIntArray(String input, String label) {
        String[] parts = input.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Format " + label + " tidak valid: " + parts[i]);
            }
        }
        return values;
    }

    private static long[] parseLongArray(String input, String label) {
        String[] parts = input.split(",");
        long[] values = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = Long.parseLong(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Format " + label + " tidak valid: " + parts[i]);
            }
        }
        return values;
    }

    private static String runScenario(String algorithm, long seed, int population, int iterations, int count)
            throws IOException {
        // 1. Inisialisasi infrastruktur dan dataset untuk setiap skenario.
        CloudSimPlus simulation = new CloudSimPlus();
        List<Datacenter> datacenters = createDatacenters(simulation);
        List<Vm> vms = createVms();
        List<Cloudlet> tasks = loadDataset(count);
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(simulation);
        broker.setDatacenterMapper(
                (defaultDc, vm) -> datacenters.get((int) Math.min(vm.getId() / VMS_PER_DC, DC_COUNT - 1)));
        broker.submitVmList(vms);

        // 2. Pencarian assignment task ke VM dengan parameter dinamis (seed,
        // population, iterations).
        double[] lengths = tasks.stream().mapToDouble(Cloudlet::getLength).toArray();
        double[] mips = vms.stream().mapToDouble(Vm::getMips).toArray();
        int[] cores = vms.stream().mapToInt(vm -> (int) vm.getPesNumber()).toArray();
        MetaScheduler scheduler = new MetaScheduler(lengths, mips, cores);
        MetaScheduler.Result schedule = algorithm.equals("ACO")
                ? scheduler.runAco(seed, population, iterations)
                : scheduler.runGwo(seed, population, iterations);

        // 3. Binding task dan eksekusi CloudSim dalam urutan dataset.
        for (int task = 0; task < tasks.size(); task++)
            broker.bindCloudletToVm(tasks.get(task), vms.get(schedule.assignment()[task]));
        broker.submitCloudletList(tasks);
        // Rekam alokasi sebelum VM dilepas dari host pada akhir simulasi.
        Map<Vm, Host> vmHosts = new IdentityHashMap<>();
        for (Vm vm : vms)
            vm.addOnHostAllocationListener(event -> vmHosts.put(event.getVm(), event.getHost()));
        simulation.start();
        if (!mappingPrinted) {
            printVmHostMapping(vms, datacenters, vmHosts);
            mappingPrinted = true;
        }

        // 4. Validasi penyelesaian task dan perhitungan metrik.
        List<Cloudlet> finished = broker.getCloudletFinishedList();
        if (finished.size() != count || finished.stream().anyMatch(task -> task.getStatus() != Cloudlet.Status.SUCCESS))
            throw new IllegalStateException("Task tidak selesai seluruhnya: " + finished.size() + "/" + count);
        String row = calculateMetrics(algorithm, seed, population, iterations, count, schedule.makespan(), finished,
                vms);
        System.out.printf(
                "%s | seed=%d | pop=%d | iter=%d | %d/%d task selesai | prediksi %.2f detik | aktual %.2f detik%n",
                algorithm, seed, population, iterations, finished.size(), count, schedule.makespan(),
                finished.stream().mapToDouble(Cloudlet::getFinishTime).max().orElseThrow());
        return row;
    }

    private static void printVmHostMapping(List<Vm> vms, List<Datacenter> datacenters, Map<Vm, Host> vmHosts) {
        System.out.println("\n=== PEMETAAN VM -> HOST -> DATACENTER ===");
        for (Vm vm : vms) {
            Host host = vmHosts.get(vm);
            if (host == null)
                throw new IllegalStateException("Alokasi VM tidak tercatat: " + vm.getId());
            int dcNumber = datacenters.indexOf(host.getDatacenter()) + 1;
            System.out.printf("VM %2d -> Host %2d -> DC%d | MIPS %.0f | PE %d | RAM %d MB%n",
                    vm.getId(), host.getId(), dcNumber, vm.getMips(), vm.getPesNumber(), vm.getRam().getCapacity());
        }
        System.out.println();
    }

    private static List<Datacenter> createDatacenters(CloudSimPlus simulation) {
        Random random = new Random(HOST_SEED);
        List<Datacenter> datacenters = new ArrayList<>();
        for (int dc = 0; dc < DC_COUNT; dc++) {
            List<Host> hosts = new ArrayList<>();
            for (int host = 0; host < HOSTS_PER_DC; host++) {
                int cores = 4 + random.nextInt(13);
                long mips = 1000 + random.nextInt(2001);
                long ram = 4096 + random.nextInt(12289);
                List<Pe> processingElements = new ArrayList<>();
                for (int core = 0; core < cores; core++)
                    processingElements.add(new PeSimple(mips));
                Host machine = new HostSimple(ram, 10_000, 1_000_000, processingElements);
                machine.setVmScheduler(new VmSchedulerTimeShared());
                hosts.add(machine);
            }
            datacenters.add(new DatacenterSimple(simulation, hosts, new VmAllocationPolicySimple()));
        }
        return datacenters;
    }

    private static List<Vm> createVms() {
        Random random = new Random(VM_SEED);
        List<Vm> vms = new ArrayList<>();
        for (int index = 0; index < DC_COUNT * VMS_PER_DC; index++) {
            long mips = 250 + random.nextInt(751);
            int cores = 1 + random.nextInt(4);
            long ram = 512 + random.nextInt(1537);
            Vm vm = new VmSimple(mips, cores);
            vm.setRam(ram).setBw(1000).setSize(10_000);
            vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
            vms.add(vm);
        }
        return vms;
    }

    private static List<Cloudlet> loadDataset(int count) throws IOException {
        String path = "/dataset/GoCJ_Dataset_" + count + ".txt";
        try (InputStream input = Main.class.getResourceAsStream(path)) {
            if (input == null)
                throw new FileNotFoundException(path);
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            List<Cloudlet> tasks = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank())
                    continue;
                long length = Long.parseLong(line.trim());
                if (length <= 0)
                    throw new IllegalArgumentException("Panjang task harus positif");
                Cloudlet task = new CloudletSimple(length, 1);
                task.setFileSize(300).setOutputSize(300);
                task.setUtilizationModelCpu(new UtilizationModelDynamic(1));
                tasks.add(task);
            }
            if (tasks.size() != count)
                throw new IllegalArgumentException("Jumlah task di dataset tidak cocok");
            return tasks;
        }
    }

    private static String calculateMetrics(String algorithm, long seed, int population, int iterations,
            int count, double prediction, List<Cloudlet> finished, List<Vm> vms) {
        double makespan = finished.stream().mapToDouble(Cloudlet::getFinishTime).max().orElseThrow();
        Map<Vm, Double> vmFinish = new HashMap<>(), vmBusy = new HashMap<>();
        for (Cloudlet task : finished) {
            Vm vm = task.getVm();
            vmFinish.merge(vm, task.getFinishTime(), Math::max);
            vmBusy.merge(vm, task.getLength() / vm.getMips(), Double::sum);
        }
        double[] finishTimes = new double[vms.size()];
        double utilization = 0, cost = 0;
        for (int index = 0; index < vms.size(); index++) {
            Vm vm = vms.get(index);
            finishTimes[index] = vmFinish.getOrDefault(vm, 0.0);
            utilization += vmBusy.getOrDefault(vm, 0.0) / (vm.getPesNumber() * makespan);
            cost += finishTimes[index] * vm.getMips() * vm.getPesNumber() * COST_PER_MIPS_SECOND;
        }
        double mean = Arrays.stream(finishTimes).average().orElseThrow();
        double variance = Arrays.stream(finishTimes).map(time -> (time - mean) * (time - mean)).average().orElseThrow();
        double imbalance = Math.sqrt(variance);
        double gapPercent = Math.abs(makespan - prediction) / prediction * 100;
        return String.format(Locale.US, "%s,%d,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                algorithm, seed, population, iterations, count, prediction, makespan, gapPercent,
                finished.size() / makespan, 100 * utilization / vms.size(), imbalance, cost);
    }

    // Flush tanpa menutup stream terminal.
    private static class TeeOutputStream extends OutputStream {
        private final OutputStream terminal, file;

        TeeOutputStream(OutputStream terminal, OutputStream file) {
            this.terminal = terminal;
            this.file = file;
        }

        public void write(int value) throws IOException {
            terminal.write(value);
            file.write(value);
        }

        public void write(byte[] data, int offset, int length) throws IOException {
            terminal.write(data, offset, length);
            file.write(data, offset, length);
        }

        public void flush() throws IOException {
            terminal.flush();
            file.flush();
        }

        public void close() throws IOException {
            flush();
        }
    }
}