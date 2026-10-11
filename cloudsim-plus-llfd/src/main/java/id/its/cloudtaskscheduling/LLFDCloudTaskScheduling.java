package id.its.cloudtaskscheduling;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.allocationpolicies.VmAllocationPolicyRoundRobin;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;
import org.cloudsimplus.util.Log;
import ch.qos.logback.classic.Level;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Least-Loaded First-Fit Decreasing (LLFD) + CloudSim Plus
 *
 * Project:
 * Cloud Task Scheduling — Kelompok 4
 *
 * Architecture:
 * 1 Datacenter
 * 4 heterogeneous Hosts
 * 8 heterogeneous VMs
 * Cloudlets from Google Cluster Workload Trace v1 (100, 500, 1000 tasks)
 *
 * LLFD Algorithm:
 * 1. Sort tasks in Decreasing order of lengthMI (First-Fit Decreasing).
 * 2. For each task pick the VM with the lowest current accumulated load
 *    (Least-Loaded) that still satisfies the task's PE and RAM constraints.
 * 3. Update the VM's accumulated load after each assignment.
 *
 * Objectives:
 * - Minimise makespan.
 * - Minimise energy consumption.
 */
public class LLFDCloudTaskScheduling {

    /* =========================================================
       CLOUD PARAMETERS
       ========================================================= */

    private static final int NUMBER_OF_HOSTS = 4;
    private static final int NUMBER_OF_VMS   = 8;

    /*
     * Host configuration:
     *   H0 =  8 PE, 16 GB RAM, 1000 GB storage, 10 Gbps BW
     *   H1 =  8 PE, 16 GB RAM, 1000 GB storage, 10 Gbps BW
     *   H2 = 16 PE, 32 GB RAM, 2000 GB storage, 10 Gbps BW
     *   H3 = 16 PE, 32 GB RAM, 2000 GB storage, 10 Gbps BW
     */
    private static final int[]  HOST_PES        = {8,  8,  16, 16};
    private static final long[] HOST_RAM_MB     = {16_384, 16_384, 32_768, 32_768};
    private static final long[] HOST_STORAGE_MB = {1_000_000, 1_000_000, 2_000_000, 2_000_000};
    private static final long[] HOST_BW_MBPS    = {10_000, 10_000, 10_000, 10_000};
    private static final int    HOST_MIPS_PER_PE = 2500;

    /*
     * VM configuration:
     *   VM0-VM1  → Host 0 | 2 PE,  4 GB RAM, 100 GB, 1000 MIPS/PE
     *   VM2-VM3  → Host 1 | 4 PE,  8 GB RAM, 200 GB, 1500 MIPS/PE
     *   VM4-VM5  → Host 2 | 4 PE,  8 GB RAM, 200 GB, 2000 MIPS/PE
     *   VM6-VM7  → Host 3 | 8 PE, 16 GB RAM, 500 GB, 2500 MIPS/PE
     */
    private static final int[]  VM_PES     = {2, 2, 4, 4, 4, 4, 8, 8};
    private static final double[] VM_MIPS  = {1000, 1000, 1500, 1500, 2000, 2000, 2500, 2500};
    private static final long[] VM_RAM_MB  = {4096, 4096, 8192, 8192, 8192, 8192, 16384, 16384};
    private static final long[] VM_SIZE_MB = {100_000, 100_000, 200_000, 200_000,
                                              200_000, 200_000, 500_000, 500_000};

    /* Energy model: static + dynamic power per VM (W) */
    private static final double[] VM_POWER_IDLE_W = {
            50, 50, 75, 75, 100, 100, 150, 150
    };
    private static final double[] VM_POWER_BUSY_W = {
            150, 150, 220, 220, 300, 300, 450, 450
    };

    /* =========================================================
       CLOUDSIM OBJECTS
       ========================================================= */

    private static CloudSimPlus            simulation;
    private static Datacenter              datacenter;
    private static DatacenterBrokerSimple  broker;
    private static List<Host>              hostList;
    private static List<Vm>               vmList;
    private static List<Cloudlet>         cloudletList;

    /* =========================================================
       DATASET MODEL & RESULT STRUCT
       ========================================================= */

    private static class TaskData {
        int  taskId;
        long lengthMI;
        int  pes;
        long ramMB;
        int  priority;

        TaskData(int taskId, long lengthMI, int pes, long ramMB, int priority) {
            this.taskId   = taskId;
            this.lengthMI = lengthMI;
            this.pes      = pes;
            this.ramMB    = ramMB;
            this.priority = priority;
        }
    }

    public static class SimulationResult {
        public String algorithm;
        public int taskCount;
        public double makespan;
        public double energy;
        public double avgUtil;
        public double throughput;

        public SimulationResult(String algorithm, int taskCount, double makespan, double energy, double avgUtil, double throughput) {
            this.algorithm = algorithm;
            this.taskCount = taskCount;
            this.makespan = makespan;
            this.energy = energy;
            this.avgUtil = avgUtil;
            this.throughput = throughput;
        }
    }

    /* =========================================================
       MAIN
       ========================================================= */

    public static void main(String[] args) {
        /* Suppress verbose CloudSim scheduler warnings during massive multi-task simulation */
        Log.setLevel(Level.ERROR);

        /* Check arguments */
        if (args.length > 0) {
            String command = args[0].toLowerCase();
            if ("all".equals(command) || "compare".equals(command) || "multi".equals(command)) {
                runAllComparisons();
                return;
            } else if ("fcfs".equals(command)) {
                String dataset = args.length > 1 ? args[1] : "dataset/tasks.csv";
                String suffix = args.length > 2 ? args[2] : "";
                runFcfsSimulation(dataset, suffix);
                return;
            } else if ("llfd".equals(command)) {
                String dataset = args.length > 1 ? args[1] : "dataset/tasks.csv";
                String suffix = args.length > 2 ? args[2] : "";
                runLlfdSimulation(dataset, suffix);
                return;
            }
        }

        /* Default fallback: run LLFD with default dataset */
        runLlfdSimulation("dataset/tasks.csv", "");
    }

    /* =========================================================
       SIMULATION EXECUTION: LLFD
       ========================================================= */

    public static SimulationResult runLlfdSimulation(String datasetPath, String suffix) {
        banner("LLFD - LEAST-LOADED FIRST-FIT DECREASING", "Dataset: " + datasetPath);
        System.out.println();

        try {
            List<TaskData> taskDataList = loadDataset(datasetPath);
            System.out.printf("[INFO] Dataset loaded: %d tasks%n%n", taskDataList.size());

            simulation = new CloudSimPlus();
            datacenter = createDatacenter();
            broker = new DatacenterBrokerSimple(simulation);
            vmList = createVms();

            cloudletList = createCloudlets(taskDataList, true);

            System.out.println("=================================================");
            System.out.println(" LLFD SCHEDULING — Task-to-VM Mapping");
            System.out.println("=================================================");

            int[] mapping = llfdSchedule(cloudletList, vmList);

            broker.submitVmList(vmList);
            for (int i = 0; i < cloudletList.size(); i++) {
                cloudletList.get(i).setVm(vmList.get(mapping[i]));
            }
            broker.submitCloudletList(cloudletList);

            System.out.println();
            System.out.println("[INFO] Starting CloudSim simulation ...");
            simulation.start();
            printVmAllocationLog(vmList);
            System.out.println("[INFO] Simulation finished.");
            System.out.println();

            List<Cloudlet> finished = broker.getCloudletFinishedList();
            double makespan   = computeMakespan(finished);
            double energy     = computeEnergy(finished, mapping);
            double avgUtil    = computeAvgUtilization(finished, mapping, makespan);
            double throughput = finished.size() / makespan;

            printCloudletResults(finished, mapping);
            printMetrics(makespan, energy, avgUtil, throughput, finished.size());

            String mapPath = suffix.isEmpty() ? "results/llfd_mapping.csv" : "results/llfd_mapping_" + suffix + ".csv";
            String metPath = suffix.isEmpty() ? "results/metrics.txt" : "results/metrics_" + suffix + ".txt";

            saveMapping(finished, mapping, mapPath);
            saveMetrics(makespan, energy, avgUtil, throughput, finished.size(), metPath);

            // Also keep standard metrics.txt updated if running default or 100
            if ("100".equals(suffix)) {
                saveMetrics(makespan, energy, avgUtil, throughput, finished.size(), "results/metrics.txt");
            }

            System.out.printf("[INFO] LLFD Results saved to %s%n", metPath);

            return new SimulationResult("LLFD", finished.size(), makespan, energy, avgUtil, throughput);

        } catch (Exception e) {
            System.err.println("[ERROR] LLFD simulation failed: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /* =========================================================
       SIMULATION EXECUTION: FCFS
       ========================================================= */

    public static SimulationResult runFcfsSimulation(String datasetPath, String suffix) {
        banner("FCFS BASELINE", "Dataset: " + datasetPath);
        System.out.println();

        try {
            List<TaskData> taskDataList = loadDataset(datasetPath);
            System.out.printf("[INFO] Dataset loaded: %d tasks%n%n", taskDataList.size());

            simulation = new CloudSimPlus();
            datacenter = createDatacenter();
            broker     = new DatacenterBrokerSimple(simulation);
            vmList     = createVms(true);
            cloudletList = createCloudlets(taskDataList, false);

            /* FCFS: tasks in arrival order, assigned round-robin to feasible VMs */
            int[] mapping = new int[cloudletList.size()];
            int vmCursor = 0;
            for (int i = 0; i < cloudletList.size(); i++) {
                Cloudlet cl = cloudletList.get(i);
                int clPes = (int) cl.getPesNumber();
                int chosenVm = -1;
                for (int attempt = 0; attempt < vmList.size(); attempt++) {
                    int candidate = (vmCursor + attempt) % vmList.size();
                    if (vmList.get(candidate).getPesNumber() >= clPes) {
                        chosenVm = candidate;
                        vmCursor = (candidate + 1) % vmList.size();
                        break;
                    }
                }
                if (chosenVm == -1) {
                    chosenVm = vmCursor % vmList.size();
                }
                cl.setVm(vmList.get(chosenVm));
                mapping[i] = chosenVm;
            }

            broker.submitVmList(vmList);
            broker.submitCloudletList(cloudletList);

            System.out.println("[INFO] Starting FCFS CloudSim simulation ...");
            simulation.start();
            printVmAllocationLog(vmList);
            System.out.println("[INFO] Simulation finished.");
            System.out.println();

            List<Cloudlet> finished  = broker.getCloudletFinishedList();

            double makespan   = computeMakespan(finished);
            double energy     = computeEnergy(finished, mapping);
            double avgUtil    = computeAvgUtilization(finished, mapping, makespan);
            double throughput = finished.size() / makespan;

            printCloudletResults(finished, mapping);
            printMetrics(makespan, energy, avgUtil, throughput, finished.size());

            String metPath = suffix.isEmpty() ? "results/fcfs_metrics.txt" : "results/fcfs_metrics_" + suffix + ".txt";
            saveMetrics(makespan, energy, avgUtil, throughput, finished.size(), metPath);

            if ("100".equals(suffix)) {
                saveMetrics(makespan, energy, avgUtil, throughput, finished.size(), "results/fcfs_metrics.txt");
            }

            System.out.printf("[INFO] FCFS results saved to %s%n", metPath);

            return new SimulationResult("FCFS", finished.size(), makespan, energy, avgUtil, throughput);

        } catch (Exception e) {
            System.err.println("[ERROR] FCFS simulation failed: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /* =========================================================
       MULTI-DATASET BENCHMARK (100, 500, 1000 TASKS)
       ========================================================= */

    public static void runAllComparisons() {
        int[] datasetSizes = {100, 500, 1000};
        List<SimulationResult> llfdResults = new ArrayList<>();
        List<SimulationResult> fcfsResults = new ArrayList<>();

        System.out.println("================================================================================");
        System.out.println(" BATCH EXPERIMENT: LLFD vs FCFS across 100, 500, and 1000 Task Datasets");
        System.out.println("================================================================================");

        for (int size : datasetSizes) {
            String path = "dataset/tasks_" + size + ".csv";
            File f = new File(path);
            if (!f.exists() && size == 100) {
                path = "dataset/tasks.csv";
            }

            System.out.printf("%n>>> RUNNING EXPERIMENT FOR %d TASKS <<<%n", size);
            SimulationResult llfd = runLlfdSimulation(path, String.valueOf(size));
            SimulationResult fcfs = runFcfsSimulation(path, String.valueOf(size));

            if (llfd != null) llfdResults.add(llfd);
            if (fcfs != null) fcfsResults.add(fcfs);
        }

        /* Generate & print comparative table */
        StringBuilder sb = new StringBuilder();
        sb.append("===================================================================================================\n");
        sb.append("                       MULTI-DATASET PERFORMANCE EVALUATION SUMMARY                                 \n");
        sb.append("===================================================================================================\n");
        sb.append(String.format("%-10s | %-6s | %-16s | %-18s | %-14s | %-16s%n",
                "Dataset", "Algo", "Makespan (s)", "Total Energy (J)", "Avg VM Util(%)", "Throughput (t/s)"));
        sb.append("---------------------------------------------------------------------------------------------------\n");

        for (int i = 0; i < datasetSizes.length; i++) {
            int size = datasetSizes[i];
            if (i < llfdResults.size() && i < fcfsResults.size()) {
                SimulationResult l = llfdResults.get(i);
                SimulationResult f = fcfsResults.get(i);

                double diffMakespan = ((l.makespan - f.makespan) / f.makespan) * 100.0;
                double diffEnergy   = ((l.energy - f.energy) / f.energy) * 100.0;
                double diffUtil     = l.avgUtil - f.avgUtil;
                double diffThroughput = ((l.throughput - f.throughput) / f.throughput) * 100.0;

                sb.append(String.format("%-10s | %-6s | %16.4f | %18.4f | %14.2f | %16.6f%n",
                        size + " Tasks", "LLFD", l.makespan, l.energy, l.avgUtil, l.throughput));
                sb.append(String.format("%-10s | %-6s | %16.4f | %18.4f | %14.2f | %16.6f%n",
                        "", "FCFS", f.makespan, f.energy, f.avgUtil, f.throughput));
                sb.append(String.format("%-10s | %-6s | %15.2f%% | %17.2f%% | %+13.2f%% | %+15.2f%%%n",
                        "", "Diff", diffMakespan, diffEnergy, diffUtil, diffThroughput));
                sb.append("---------------------------------------------------------------------------------------------------\n");
            }
        }
        sb.append("Note: Negative diff on Makespan & Energy means LLFD is better (faster execution & lower energy).\n");
        sb.append("===================================================================================================\n");

        System.out.println();
        System.out.print(sb.toString());

        try {
            String summaryFile = "results/summary_comparison.txt";
            new File(summaryFile).getParentFile().mkdirs();
            try (PrintWriter pw = new PrintWriter(new FileWriter(summaryFile))) {
                pw.print(sb.toString());
            }
            System.out.printf("[INFO] Comprehensive summary saved to %s%n", summaryFile);
        } catch (IOException e) {
            System.err.println("[WARN] Failed to write summary_comparison.txt: " + e.getMessage());
        }
    }

    /* =========================================================
       LLFD ALGORITHM
       ========================================================= */

    private static int[] llfdSchedule(List<Cloudlet> cloudlets, List<Vm> vms) {

        int n = cloudlets.size();
        int m = vms.size();

        double[] vmLoad = new double[m];
        int[] mapping   = new int[n];

        // Print header
        System.out.printf("%-6s %-10s %-6s %-8s %-5s%n",
                "Task#", "LengthMI", "PEs", "Assigned", "VMLoad(s)");
        System.out.println("-".repeat(45));

        for (int i = 0; i < n; i++) {
            Cloudlet cl  = cloudlets.get(i);
            int  clPes   = (int) cl.getPesNumber();
            long clLen   = cl.getLength();

            int  bestVm  = -1;
            double bestLoad = Double.MAX_VALUE;

            for (int j = 0; j < m; j++) {
                Vm vm = vms.get(j);
                if (vm.getPesNumber() < clPes) continue;
                if (vmLoad[j] < bestLoad) {
                    bestLoad = vmLoad[j];
                    bestVm   = j;
                }
            }

            if (bestVm == -1) {
                for (int j = 0; j < m; j++) {
                    if (vmLoad[j] < bestLoad) {
                        bestLoad = vmLoad[j];
                        bestVm   = j;
                    }
                }
            }

            mapping[i]  = bestVm;
            double execT = clLen / VM_MIPS[bestVm];
            vmLoad[bestVm] += execT;

            if (n <= 100 || i < 20 || i >= n - 10) {
                System.out.printf("%-6d %-10d %-6d VM%-6d %.4f%n",
                        i + 1, clLen, clPes, bestVm + 1, vmLoad[bestVm]);
            } else if (i == 20) {
                System.out.printf("... [%d tasks omitted for conciseness in console] ...%n", n - 30);
            }
        }

        System.out.println("-".repeat(45));
        System.out.println();
        System.out.println("[INFO] VM Load summary after LLFD mapping:");
        System.out.printf("%-8s %-10s %-12s %-10s%n", "VM", "MIPS/PE", "Load(s)", "Cloudlets");
        System.out.println("-".repeat(45));
        int[] taskCount = new int[m];
        for (int i = 0; i < n; i++) taskCount[mapping[i]]++;
        for (int j = 0; j < m; j++) {
            System.out.printf("%-8d %-10.0f %-12.4f %-10d%n",
                    j + 1, VM_MIPS[j], vmLoad[j], taskCount[j]);
        }
        System.out.println("-".repeat(45));
        System.out.println();

        return mapping;
    }

    /* =========================================================
       INFRASTRUCTURE CREATION
       ========================================================= */

    private static Datacenter createDatacenter() {
        System.out.println("=================================================");
        System.out.println(" DATACENTER — Host Creation Log");
        System.out.println("=================================================");

        hostList = new ArrayList<>(NUMBER_OF_HOSTS);

        for (int i = 0; i < NUMBER_OF_HOSTS; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < HOST_PES[i]; p++) {
                peList.add(new PeSimple(HOST_MIPS_PER_PE));
            }

            Host host = new HostSimple(HOST_RAM_MB[i], HOST_BW_MBPS[i], HOST_STORAGE_MB[i], peList);
            host.setVmScheduler(new VmSchedulerTimeShared());
            hostList.add(host);

            System.out.printf("[HOST CREATED] Host-%d | PEs: %2d | RAM: %6d MB | " +
                              "Storage: %7d MB | BW: %5d Mbps | MIPS/PE: %d%n",
                    i + 1, HOST_PES[i], HOST_RAM_MB[i], HOST_STORAGE_MB[i],
                    HOST_BW_MBPS[i], HOST_MIPS_PER_PE);
        }

        System.out.println("-".repeat(80));
        System.out.printf("[INFO] Datacenter ready: %d hosts created.%n%n", NUMBER_OF_HOSTS);

        return new DatacenterSimple(simulation, hostList, new VmAllocationPolicyRoundRobin());
    }

    private static List<Vm> createVms() {
        return createVms(false);
    }

    private static List<Vm> createVms(boolean spaceShared) {
        System.out.println("=================================================");
        System.out.println(" VM CREATION LOG" + (spaceShared ? " (SpaceShared FIFO)" : " (TimeShared)"));
        System.out.println("=================================================");

        List<Vm> vms = new ArrayList<>(NUMBER_OF_VMS);

        for (int i = 0; i < NUMBER_OF_VMS; i++) {
            Vm vm = new VmSimple(VM_MIPS[i], VM_PES[i]);
            vm.setRam(VM_RAM_MB[i])
              .setSize(VM_SIZE_MB[i])
              .setBw(1000)
              .setCloudletScheduler(spaceShared ? new CloudletSchedulerSpaceShared() : new CloudletSchedulerTimeShared());

            vms.add(vm);

            int targetHost = i / 2;
            System.out.printf("[VM CREATED]  VM-%-2d | PEs: %2d | RAM: %6d MB | " +
                              "Size: %7d MB | MIPS/PE: %4.0f | Expected Host: Host-%d%n",
                    i + 1, VM_PES[i], VM_RAM_MB[i], VM_SIZE_MB[i], VM_MIPS[i], targetHost + 1);
        }

        System.out.println("-".repeat(80));
        System.out.printf("[INFO] %d VMs created.%n%n", NUMBER_OF_VMS);

        return vms;
    }

    private static void printVmAllocationLog(List<Vm> vms) {
        System.out.println("=================================================");
        System.out.println(" VM ALLOCATION LOG — Actual Host Placement");
        System.out.println("=================================================");
        System.out.printf("%-8s %-14s %-12s %-12s %-10s%n",
                "VM", "Allocation", "Actual Host", "Host PEs", "VM PEs");
        System.out.println("-".repeat(65));

        int allocated = 0;
        for (Vm vm : vms) {
            Host host = vm.getHost();
            boolean isAllocated = host != null && host != Host.NULL;
            String allocation = isAllocated ? "ALLOCATED" : "FAILED";
            String hostName = isAllocated ? "Host-" + ((int) host.getId() + 1) : "-";
            int hostPes = isAllocated ? host.getPeList().size() : 0;

            if (isAllocated) {
                allocated++;
            }

            System.out.printf("%-8s %-14s %-12s %-12d %-10d%n",
                    "VM-" + ((int) vm.getId() + 1),
                    allocation,
                    hostName,
                    hostPes,
                    (int) vm.getPesNumber());
        }

        System.out.println("-".repeat(65));
        System.out.printf("[INFO] VM allocation result: %d/%d VMs allocated.%n",
                allocated, vms.size());

        for (Host host : hostList) {
            long hostVmCount = vms.stream().filter(vm -> vm.getHost() == host).count();
            int usedPes = vms.stream()
                    .filter(vm -> vm.getHost() == host)
                    .mapToInt(vm -> (int) vm.getPesNumber())
                    .sum();
            System.out.printf("[HOST VM SUMMARY] Host-%d | VMs: %d | Used PEs: %d/%d%n",
                    (int) host.getId() + 1,
                    hostVmCount,
                    usedPes,
                    host.getPeList().size());
        }
        System.out.println();
    }

    private static List<Cloudlet> createCloudlets(List<TaskData> taskDataList,
                                                   boolean sortDecreasing) {

        System.out.println("=================================================");
        System.out.println(" CLOUDLET CREATION LOG");
        System.out.println("=================================================");

        if (sortDecreasing) {
            taskDataList = new ArrayList<>(taskDataList);
            taskDataList.sort(Comparator.comparingLong((TaskData t) -> t.lengthMI).reversed());
            System.out.println("[INFO] Tasks sorted in Decreasing order of lengthMI (FFD step).");
        }

        List<Cloudlet> cloudlets = new ArrayList<>(taskDataList.size());
        int n = taskDataList.size();
        for (int i = 0; i < n; i++) {
            TaskData td = taskDataList.get(i);
            UtilizationModelDynamic cpuModel = new UtilizationModelDynamic(0.5);

            Cloudlet cl = new CloudletSimple(td.lengthMI, td.pes);
            cl.setFileSize(300)
              .setOutputSize(300)
              .setUtilizationModelCpu(cpuModel);

            cloudlets.add(cl);

            if (n <= 100 || i < 15 || i >= n - 5) {
                System.out.printf("[CLOUDLET]    Task-%-4d | LengthMI: %6d | PEs: %d | " +
                                  "RAM: %6d MB | Priority: %d%n",
                        td.taskId, td.lengthMI, td.pes, td.ramMB, td.priority);
            } else if (i == 15) {
                System.out.printf("... [%d cloudlets creation lines omitted in console] ...%n", n - 20);
            }
        }

        System.out.println("-".repeat(80));
        System.out.printf("[INFO] %d cloudlets created.%n%n", cloudlets.size());

        return cloudlets;
    }

    /* =========================================================
       METRICS
       ========================================================= */

    private static double computeMakespan(List<Cloudlet> cloudlets) {
        return cloudlets.stream()
                .mapToDouble(Cloudlet::getFinishTime)
                .max()
                .orElse(0.0);
    }

    private static double computeEnergy(List<Cloudlet> cloudlets, int[] mapping) {
        int m = vmList.size();
        double[] vmBusyTime = new double[m];

        for (int i = 0; i < cloudlets.size(); i++) {
            Cloudlet cl      = cloudlets.get(i);
            int vmIdx        = mapping[i];
            double execTime  = cl.getFinishTime() - cl.getExecStartTime();
            vmBusyTime[vmIdx] += execTime;
        }

        double makespan = computeMakespan(cloudlets);
        double totalEnergy = 0.0;
        for (int j = 0; j < m; j++) {
            double busyT  = Math.min(vmBusyTime[j], makespan);
            double idleT  = Math.max(0, makespan - busyT);
            totalEnergy  += busyT * VM_POWER_BUSY_W[j] + idleT * VM_POWER_IDLE_W[j];
        }
        return totalEnergy;
    }

    private static double computeAvgUtilization(List<Cloudlet> cloudlets,
                                                 int[] mapping,
                                                 double makespan) {
        if (makespan <= 0) return 0;
        int m = vmList.size();
        double[] vmBusyTime = new double[m];

        for (int i = 0; i < cloudlets.size(); i++) {
            Cloudlet cl     = cloudlets.get(i);
            int vmIdx       = mapping[i];
            double execTime = cl.getFinishTime() - cl.getExecStartTime();
            vmBusyTime[vmIdx] += execTime;
        }

        double totalUtil = 0;
        for (int j = 0; j < m; j++) {
            totalUtil += Math.min(vmBusyTime[j], makespan) / makespan;
        }
        return (totalUtil / m) * 100.0;
    }

    /* =========================================================
       PRINTING
       ========================================================= */

    private static void printCloudletResults(List<Cloudlet> cloudlets, int[] mapping) {
        int n = cloudlets.size();
        System.out.println("=================================================");
        System.out.println(" SIMULATION RESULTS — Cloudlet Execution");
        System.out.println("=================================================");
        System.out.printf("%-8s %-6s %-10s %-10s %-10s %-10s %-8s%n",
                "CL#", "VM", "Status", "Length(MI)", "StartTime", "FinishTime", "ExecTime");
        System.out.println("-".repeat(70));

        for (int i = 0; i < n; i++) {
            if (n <= 100 || i < 15 || i >= n - 5) {
                Cloudlet cl = cloudlets.get(i);
                System.out.printf("%-8d %-6d %-10s %-10d %-10.2f %-10.2f %-8.2f%n",
                        i + 1,
                        mapping[i] + 1,
                        cl.getStatus().name(),
                        cl.getLength(),
                        cl.getExecStartTime(),
                        cl.getFinishTime(),
                        cl.getFinishTime() - cl.getExecStartTime());
            } else if (i == 15) {
                System.out.printf("... [%d execution result rows omitted in console] ...%n", n - 20);
            }
        }
        System.out.println("-".repeat(70));
    }

    private static void printMetrics(double makespan, double energy,
                                      double avgUtil, double throughput,
                                      int taskCount) {
        System.out.println();
        System.out.println("=================================================");
        System.out.println(" PERFORMANCE METRICS");
        System.out.println("=================================================");
        System.out.printf("  Tasks Completed  : %d%n",       taskCount);
        System.out.printf("  Makespan         : %.4f s%n",   makespan);
        System.out.printf("  Total Energy     : %.4f J%n",   energy);
        System.out.printf("  Avg VM Util.     : %.2f %%%n",  avgUtil);
        System.out.printf("  Throughput       : %.6f tasks/s%n", throughput);
        System.out.println("=================================================");
        System.out.println();
    }

    private static void banner(String title, String subtitle) {
        System.out.println();
        System.out.println("=================================================");
        System.out.println(" " + title);
        System.out.println(" " + subtitle);
        System.out.println("=================================================");
    }

    /* =========================================================
       PERSISTENCE
       ========================================================= */

    private static void saveMapping(List<Cloudlet> cloudlets, int[] mapping,
                                     String path) throws IOException {
        new File(path).getParentFile().mkdirs();
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            pw.println("cloudletIndex,vmId,lengthMI,startTime,finishTime,execTime,status");
            for (int i = 0; i < cloudlets.size(); i++) {
                Cloudlet cl = cloudlets.get(i);
                pw.printf("%d,%d,%d,%.4f,%.4f,%.4f,%s%n",
                        i + 1,
                        mapping[i] + 1,
                        cl.getLength(),
                        cl.getExecStartTime(),
                        cl.getFinishTime(),
                        cl.getFinishTime() - cl.getExecStartTime(),
                        cl.getStatus().name());
            }
        }
        System.out.printf("[INFO] Mapping written → %s%n", path);
    }

    private static void saveMetrics(double makespan, double energy,
                                     double avgUtil, double throughput,
                                     int taskCount, String path) throws IOException {
        new File(path).getParentFile().mkdirs();
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            pw.println("=================================================");
            pw.println(" LLFD Cloud Task Scheduling — Performance Metrics");
            pw.println("=================================================");
            pw.printf("Tasks Completed : %d%n",       taskCount);
            pw.printf("Makespan        : %.4f s%n",   makespan);
            pw.printf("Total Energy    : %.4f J%n",   energy);
            pw.printf("Avg VM Util.    : %.2f %%%n",  avgUtil);
            pw.printf("Throughput      : %.6f tasks/s%n", throughput);
        }
        System.out.printf("[INFO] Metrics written  → %s%n", path);
    }

    /* =========================================================
       DATASET LOADER
       ========================================================= */

    private static List<TaskData> loadDataset(String csvPath) throws IOException {
        System.out.println("=================================================");
        System.out.println(" DATASET LOAD LOG: " + csvPath);
        System.out.println("=================================================");

        List<TaskData> tasks = new ArrayList<>();
        File file = new File(csvPath);

        if (!file.exists()) {
            throw new IOException("Dataset not found: " + file.getAbsolutePath());
        }

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine();
            int row = 0;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",");
                if (parts.length < 5) continue;

                int  taskId   = Integer.parseInt(parts[0].trim());
                long lengthMI = Long.parseLong(parts[1].trim());
                int  pes      = Integer.parseInt(parts[2].trim());
                long ramMB    = Long.parseLong(parts[3].trim());
                int  priority = Integer.parseInt(parts[4].trim());

                tasks.add(new TaskData(taskId, lengthMI, pes, ramMB, priority));
                row++;

                if (row <= 15 || row >= 95) {
                    // avoid spamming terminal when row count is large
                }
            }
        }

        System.out.printf("[INFO] Total tasks loaded: %d%n%n", tasks.size());
        return tasks;
    }
}
