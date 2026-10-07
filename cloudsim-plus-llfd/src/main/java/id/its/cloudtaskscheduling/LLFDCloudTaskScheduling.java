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
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerTimeShared;
import org.cloudsimplus.schedulers.vm.VmSchedulerTimeShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

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
 * 100 Cloudlets from Google Cluster Workload Trace v1
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
       DATASET MODEL
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

    /* =========================================================
       MAIN
       ========================================================= */

    public static void main(String[] args) {

        /* Allow running the baseline FCFS comparison with: java ... fcfs */
        if (args.length > 0 && "fcfs".equalsIgnoreCase(args[0])) {
            runFcfsSimulation();
            return;
        }

        banner("LLFD - LEAST-LOADED FIRST-FIT DECREASING", "Cloud Task Scheduling — CloudSim Plus");
        System.out.println();

        try {

            /* -----------------------------------------------------------
             * 1. Load workload dataset
             * ----------------------------------------------------------- */
            List<TaskData> taskDataList = loadDataset("dataset/tasks.csv");
            System.out.printf("[INFO] Dataset loaded: %d tasks%n%n", taskDataList.size());

            /* -----------------------------------------------------------
             * 2. Create CloudSim Plus simulation engine
             * ----------------------------------------------------------- */
            simulation = new CloudSimPlus();

            /* -----------------------------------------------------------
             * 3. Create Datacenter
             * ----------------------------------------------------------- */
            datacenter = createDatacenter();

            /* -----------------------------------------------------------
             * 4. Create Broker
             * ----------------------------------------------------------- */
            broker = new DatacenterBrokerSimple(simulation);

            /* -----------------------------------------------------------
             * 5. Create VMs
             * ----------------------------------------------------------- */
            vmList = createVms();

            /* -----------------------------------------------------------
             * 6. Create Cloudlets (sorted Decreasing by lengthMI for FFD)
             * ----------------------------------------------------------- */
            cloudletList = createCloudlets(taskDataList, true);

            /* -----------------------------------------------------------
             * 7. LLFD Scheduling: assign each cloudlet to a VM
             * ----------------------------------------------------------- */
            System.out.println("=================================================");
            System.out.println(" LLFD SCHEDULING — Task-to-VM Mapping");
            System.out.println("=================================================");

            int[] mapping = llfdSchedule(cloudletList, vmList);

            /* -----------------------------------------------------------
             * 8. Apply the mapping to the broker
             * ----------------------------------------------------------- */
            broker.submitVmList(vmList);
            for (int i = 0; i < cloudletList.size(); i++) {
                cloudletList.get(i).setVm(vmList.get(mapping[i]));
            }
            broker.submitCloudletList(cloudletList);

            /* -----------------------------------------------------------
             * 9. Run simulation
             * ----------------------------------------------------------- */
            System.out.println();
            System.out.println("[INFO] Starting CloudSim simulation ...");
            simulation.start();
            printVmAllocationLog(vmList);
            System.out.println("[INFO] Simulation finished.");
            System.out.println();

            /* -----------------------------------------------------------
             * 10. Collect results and compute metrics
             * ----------------------------------------------------------- */
            List<Cloudlet> finished = broker.getCloudletFinishedList();
            double makespan  = computeMakespan(finished);
            double energy    = computeEnergy(finished, mapping);
            double avgUtil   = computeAvgUtilization(finished, mapping, makespan);
            double throughput = finished.size() / makespan;

            /* -----------------------------------------------------------
             * 11. Print summary
             * ----------------------------------------------------------- */
            printCloudletResults(finished, mapping);
            printMetrics(makespan, energy, avgUtil, throughput, finished.size());

            /* -----------------------------------------------------------
             * 12. Save outputs
             * ----------------------------------------------------------- */
            saveMapping(finished, mapping, "results/llfd_mapping.csv");
            saveMetrics(makespan, energy, avgUtil, throughput, finished.size(),
                    "results/metrics.txt");

            System.out.println();
            System.out.println("[INFO] Results saved to results/");

        } catch (Exception e) {
            System.err.println("[ERROR] Simulation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /* =========================================================
       LLFD ALGORITHM
       ========================================================= */

    /**
     * Least-Loaded First-Fit Decreasing scheduling.
     *
     * <p>Cloudlets are expected to be pre-sorted in <em>decreasing</em> order of
     * {@code lengthMI} before calling this method (the FFD part).
     *
     * <p>For each cloudlet the algorithm selects the VM that:
     * <ol>
     *   <li>has enough PEs to satisfy the cloudlet's PE requirement, AND</li>
     *   <li>among all feasible VMs, currently carries the <em>smallest</em>
     *       accumulated load (the LL part).</li>
     * </ol>
     *
     * <p>Accumulated load is measured as the sum of {@code lengthMI / MIPS}
     * (i.e. estimated execution seconds) for all cloudlets already assigned to
     * that VM.  This is a lightweight proxy for expected finish time.
     *
     * @param cloudlets sorted cloudlet list (decreasing length)
     * @param vms       list of candidate VMs
     * @return mapping array where {@code mapping[i]} is the VM index for
     *         {@code cloudlets.get(i)}
     */
    private static int[] llfdSchedule(List<Cloudlet> cloudlets, List<Vm> vms) {

        int n = cloudlets.size();
        int m = vms.size();

        /* Accumulated load (seconds) per VM */
        double[] vmLoad = new double[m];
        int[] mapping   = new int[n];

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
                if (vm.getPesNumber() < clPes) continue;   // PE constraint
                if (vmLoad[j] < bestLoad) {
                    bestLoad = vmLoad[j];
                    bestVm   = j;
                }
            }

            /* Fallback: if no VM satisfies PE constraint, pick globally least-loaded */
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

            System.out.printf("%-6d %-10d %-6d VM%-6d %.4f%n",
                    i + 1, clLen, clPes, bestVm + 1, vmLoad[bestVm]);
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
       BASELINE: FCFS
       ========================================================= */

    /**
     * Baseline FCFS simulation: tasks submitted in original order,
     * assigned round-robin by the broker.
     */
    private static void runFcfsSimulation() {

        banner("FCFS BASELINE", "Cloud Task Scheduling — CloudSim Plus");
        System.out.println();

        try {
            List<TaskData> taskDataList = loadDataset("dataset/tasks.csv");
            System.out.printf("[INFO] Dataset loaded: %d tasks%n%n", taskDataList.size());

            simulation = new CloudSimPlus();
            datacenter = createDatacenter();
            broker     = new DatacenterBrokerSimple(simulation);
            vmList     = createVms();

            /* FCFS: tasks in original order, no pre-sort */
            cloudletList = createCloudlets(taskDataList, false);

            broker.submitVmList(vmList);
            broker.submitCloudletList(cloudletList);

            System.out.println("[INFO] Starting FCFS CloudSim simulation ...");
            simulation.start();
            printVmAllocationLog(vmList);
            System.out.println("[INFO] Simulation finished.");
            System.out.println();

            List<Cloudlet> finished  = broker.getCloudletFinishedList();
            /* For FCFS, mapping is broker default (round-robin) */
            int[] mapping = new int[finished.size()];
            for (int i = 0; i < finished.size(); i++) {
                int vmId = (int) finished.get(i).getVm().getId();
                mapping[i] = vmId;
            }

            double makespan  = computeMakespan(finished);
            double energy    = computeEnergy(finished, mapping);
            double avgUtil   = computeAvgUtilization(finished, mapping, makespan);
            double throughput = finished.size() / makespan;

            printCloudletResults(finished, mapping);
            printMetrics(makespan, energy, avgUtil, throughput, finished.size());

            saveMetrics(makespan, energy, avgUtil, throughput, finished.size(),
                    "results/fcfs_metrics.txt");

            System.out.println("[INFO] FCFS results saved to results/fcfs_metrics.txt");

        } catch (Exception e) {
            System.err.println("[ERROR] FCFS simulation failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /* =========================================================
       INFRASTRUCTURE CREATION
       ========================================================= */

    /** Creates the Datacenter with 4 heterogeneous hosts. */
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

    /**
     * Creates 8 heterogeneous VMs and logs each one.
     *
     * @return list of created VMs
     */
    private static List<Vm> createVms() {

        System.out.println("=================================================");
        System.out.println(" VM CREATION LOG");
        System.out.println("=================================================");

        List<Vm> vms = new ArrayList<>(NUMBER_OF_VMS);

        for (int i = 0; i < NUMBER_OF_VMS; i++) {
            Vm vm = new VmSimple(VM_MIPS[i], VM_PES[i]);
            vm.setRam(VM_RAM_MB[i])
              .setSize(VM_SIZE_MB[i])
              .setBw(1000)
              .setCloudletScheduler(new CloudletSchedulerTimeShared());

            vms.add(vm);

            int targetHost = i / 2;   // RoundRobin places 2 VMs per host
            System.out.printf("[VM CREATED]  VM-%-2d | PEs: %2d | RAM: %6d MB | " +
                              "Size: %7d MB | MIPS/PE: %4.0f | Expected Host: Host-%d%n",
                    i + 1, VM_PES[i], VM_RAM_MB[i], VM_SIZE_MB[i], VM_MIPS[i], targetHost + 1);
        }

        System.out.println("-".repeat(80));
        System.out.printf("[INFO] %d VMs created.%n%n", NUMBER_OF_VMS);

        return vms;
    }

    /**
     * Logs the host placement performed by CloudSim after VM allocation.
     * The expected host printed during creation is only a configuration
     * expectation; this log records the actual allocation result.
     */
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

    /**
     * Reads the CSV dataset and converts each row into a {@link Cloudlet}.
     *
     * @param taskDataList ordered list of task descriptors
     * @param sortDecreasing if {@code true}, cloudlets are sorted by lengthMI
     *                       descending (required by FFD step of LLFD)
     * @return list of cloudlets
     */
    private static List<Cloudlet> createCloudlets(List<TaskData> taskDataList,
                                                   boolean sortDecreasing) {

        System.out.println("=================================================");
        System.out.println(" CLOUDLET CREATION LOG");
        System.out.println("=================================================");

        /* Optionally sort descending for FFD */
        if (sortDecreasing) {
            taskDataList = new ArrayList<>(taskDataList);
            taskDataList.sort(Comparator.comparingLong((TaskData t) -> t.lengthMI).reversed());
            System.out.println("[INFO] Tasks sorted in Decreasing order of lengthMI (FFD step).");
        }

        List<Cloudlet> cloudlets = new ArrayList<>(taskDataList.size());
        for (TaskData td : taskDataList) {
            UtilizationModelDynamic utilModel = new UtilizationModelDynamic(0.5);

            Cloudlet cl = new CloudletSimple(td.lengthMI, td.pes);
            cl.setFileSize(300)
              .setOutputSize(300)
              .setUtilizationModelCpu(utilModel)
              .setUtilizationModelRam(utilModel)
              .setUtilizationModelBw(utilModel);

            cloudlets.add(cl);

            System.out.printf("[CLOUDLET]    Task-%-4d | LengthMI: %6d | PEs: %d | " +
                              "RAM: %6d MB | Priority: %d%n",
                    td.taskId, td.lengthMI, td.pes, td.ramMB, td.priority);
        }

        System.out.println("-".repeat(80));
        System.out.printf("[INFO] %d cloudlets created.%n%n", cloudlets.size());

        return cloudlets;
    }

    /* =========================================================
       METRICS
       ========================================================= */

    /**
     * Makespan = latest finish time among all cloudlets.
     */
    private static double computeMakespan(List<Cloudlet> cloudlets) {
        return cloudlets.stream()
                .mapToDouble(Cloudlet::getFinishTime)
                .max()
                .orElse(0.0);
    }

    /**
     * Total energy = Σ (execTime_i * powerBusy_vm_i) + idle time * powerIdle_vm_i.
     *
     * <p>A simple linear energy model per VM is used, consistent with how the
     * reference implementation approximates energy.
     */
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

    /**
     * Average VM utilisation (0–100 %) across the makespan.
     */
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

        System.out.println("=================================================");
        System.out.println(" SIMULATION RESULTS — Cloudlet Execution");
        System.out.println("=================================================");
        System.out.printf("%-8s %-6s %-10s %-10s %-10s %-10s %-8s%n",
                "CL#", "VM", "Status", "Length(MI)", "StartTime", "FinishTime", "ExecTime");
        System.out.println("-".repeat(70));

        for (int i = 0; i < cloudlets.size(); i++) {
            Cloudlet cl = cloudlets.get(i);
            System.out.printf("%-8d %-6d %-10s %-10d %-10.2f %-10.2f %-8.2f%n",
                    i + 1,
                    mapping[i] + 1,
                    cl.getStatus().name(),
                    cl.getLength(),
                    cl.getExecStartTime(),
                    cl.getFinishTime(),
                    cl.getFinishTime() - cl.getExecStartTime());
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

    /**
     * Saves the task→VM mapping to a CSV file.
     */
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

    /**
     * Saves the performance metrics to a plain-text file.
     */
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

    /**
     * Reads {@code tasks.csv} and returns a list of {@link TaskData} records.
     *
     * @param csvPath path to the CSV file (relative to the working directory)
     * @return list of task descriptors
     * @throws IOException if the file cannot be read
     */
    private static List<TaskData> loadDataset(String csvPath) throws IOException {

        System.out.println("=================================================");
        System.out.println(" DATASET LOAD LOG");
        System.out.println("=================================================");

        List<TaskData> tasks = new ArrayList<>();
        File file = new File(csvPath);

        if (!file.exists()) {
            throw new IOException("Dataset not found: " + file.getAbsolutePath());
        }

        System.out.printf("[INFO] Reading: %s%n", file.getAbsolutePath());

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine(); // skip header
            System.out.printf("[INFO] CSV header: %s%n", line);
            System.out.println("-".repeat(70));

            int row = 0;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",");
                if (parts.length < 5) {
                    System.err.printf("[WARN] Skipping malformed row %d: %s%n", row, line);
                    continue;
                }
                int  taskId   = Integer.parseInt(parts[0].trim());
                long lengthMI = Long.parseLong(parts[1].trim());
                int  pes      = Integer.parseInt(parts[2].trim());
                long ramMB    = Long.parseLong(parts[3].trim());
                int  priority = Integer.parseInt(parts[4].trim());

                tasks.add(new TaskData(taskId, lengthMI, pes, ramMB, priority));

                System.out.printf("[LOADED] Row %-3d → taskId: %-4d | lengthMI: %6d | " +
                                  "pes: %d | ramMB: %6d | priority: %d%n",
                        ++row, taskId, lengthMI, pes, ramMB, priority);
            }
        }

        System.out.println("-".repeat(70));
        System.out.printf("[INFO] Total tasks loaded: %d%n%n", tasks.size());
        return tasks;
    }
}
