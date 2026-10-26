<div align="center">

# ☁️ LLFD Task Scheduling Simulator

**Least-Loaded First-Fit Decreasing · CloudSim Plus · Google Cluster Trace**

![Java](https://img.shields.io/badge/Java-17%2B-orange?style=for-the-badge&logo=openjdk&logoColor=white)
![Python](https://img.shields.io/badge/Python-3.8%2B-blue?style=for-the-badge&logo=python&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.6%2B-red?style=for-the-badge&logo=apachemaven&logoColor=white)
![CloudSim](https://img.shields.io/badge/CloudSim-Plus-6f42c1?style=for-the-badge)
![License](https://img.shields.io/badge/Dataset-CC--BY-green?style=for-the-badge)

<br/>

*Simulator penjadwalan cloud task berbasis algoritma LLFD yang dibandingkan dengan FCFS baseline,*
*diuji skalabilitasnya dari 100 hingga 10.000 task menggunakan Google Cluster Workload Trace.*

</div>

---

## 👥 Anggota Tim — Kelompok 4

<div align="center">

| No | Nama | NRP |
|:--:|------|:---:|
| 1 | Kanafira Vanesha Putri | 5027241010 |
| 2 | Tiara Putri Prasetya | 5027241013 |
| 3 | Dina Rahmadani | 5027241065 |
| 4 | Zahra Khaalishah | 5027241070 |
| 5 | S. Farhan Baig | 5027241097 |

*Strategi Optimasi Komputasi Awan (A) — Semester 5*

</div>

---

## 📖 Deskripsi Proyek

Proyek ini mensimulasikan penjadwalan cloud task menggunakan algoritma **Least-Loaded First-Fit Decreasing (LLFD)** di atas platform **CloudSim Plus**. Simulator membaca task dari **Google Cluster Workload Trace**, lalu menjadwalkannya ke **8 VM** dalam **1 datacenter** dengan **4 host heterogen**.

Workload menggunakan model **Bag-of-Tasks (BoT)** — task bersifat independen tanpa dependency. Tujuan optimasi:

| Metrik | Target |
|--------|--------|
| ⚡ **Makespan** | Meminimalkan total waktu penyelesaian seluruh task |
| 🔋 **Konsumsi Energi** | Meminimalkan energi berdasarkan model linear idle/busy per VM |

---

## 🧠 Algoritma LLFD

**Least-Loaded First-Fit Decreasing** menggabungkan dua strategi klasik menjadi satu pendekatan yang efisien:

### 1️⃣ First-Fit Decreasing (FFD)
Seluruh task diurutkan **menurun** berdasarkan `lengthMI` sebelum scheduling dimulai — memastikan task-task berat dialokasikan lebih dahulu agar VM berkapasitas besar tidak dibiarkan idle.

### 2️⃣ Least-Loaded (LL)
Untuk setiap task, algoritma memilih VM yang:
1. Memenuhi syarat **jumlah PE** (`vm.PEs >= task.PEs`)
2. Memiliki **accumulated load terkecil** di antara semua VM yang layak

```
accumulated_load_vm_j += lengthMI / MIPS_vm_j
```

### 📋 Pseudocode

```
Sort tasks descending by lengthMI        // FFD step
Initialize vmLoad[0..m-1] = 0

For each task t (in sorted order):
    bestVm = argmin { vmLoad[j] : vm[j].PEs >= t.PEs }
    If no feasible VM found:
        bestVm = argmin { vmLoad[j] }    // fallback
    Assign t -> bestVm
    vmLoad[bestVm] += t.lengthMI / MIPS[bestVm]
```

### ⏱️ Kompleksitas

| | Kompleksitas |
|--|--|
| **Waktu** | `O(n log n + n × m)` — n task, m VM |
| **Ruang** | `O(m)` — hanya menyimpan vmLoad per VM |

---

## 🏗️ Arsitektur Cloud

### 🖥️ Host

| Host | PE | RAM | Storage | Bandwidth |
|:----:|:--:|:---:|:-------:|:---------:|
| Host 1 | 8 | 16 GB | 1000 GB | 10 Gbps |
| Host 2 | 8 | 16 GB | 1000 GB | 10 Gbps |
| Host 3 | 16 | 32 GB | 2000 GB | 10 Gbps |
| Host 4 | 16 | 32 GB | 2000 GB | 10 Gbps |

### 💻 Virtual Machine

| VM | Host | PE | RAM | Storage | MIPS/PE |
|:--:|:----:|:--:|:---:|:-------:|:-------:|
| VM1–VM2 | Host 1 | 2 | 4 GB | 100 GB | 1000 |
| VM3–VM4 | Host 2 | 4 | 8 GB | 200 GB | 1500 |
| VM5–VM6 | Host 3 | 4 | 8 GB | 200 GB | 2000 |
| VM7–VM8 | Host 4 | 8 | 16 GB | 500 GB | 2500 |

> `VmAllocationPolicyRoundRobin` menempatkan dua VM di setiap host secara merata.

### ⚡ Model Energi

| VM | Idle Power | Busy Power |
|:--:|:----------:|:----------:|
| VM1–VM2 | 50 W | 150 W |
| VM3–VM4 | 75 W | 220 W |
| VM5–VM6 | 100 W | 300 W |
| VM7–VM8 | 150 W | 450 W |

```
Energy = Σ (busyTime_i × powerBusy_i + idleTime_i × powerIdle_i)
```

---

## 📦 Dataset

Dataset utama: `cloudsim-plus-llfd/dataset/tasks.csv`
Hasil transformasi **100 task** dari **Google Cluster Workload Trace Version 1** ke schema CloudSim Plus.

### 🔄 Transformasi Google Trace → CloudSim Schema

| Kolom Raw | → | Kolom CloudSim | Aturan Transformasi |
|:---------:|:-:|:--------------:|---------------------|
| `NrmlTaskCores` | → | `lengthMI` | `max(1, round(NrmlTaskCores × 1_000_000))` |
| `NrmlTaskCores` | → | `pes` | `max(1, min(4, ceil(NrmlTaskCores × 32)))` |
| `NrmlTaskMem` | → | `ramMB` | `max(1024, round(NrmlTaskMem × 64 × 1024))` |
| `JobType` | → | `priority` | Langsung dari trace |

Untuk membuat ulang `tasks.csv`:
```bash
python3 cloudsim-plus-llfd/dataset/prepare_google_trace.py
```

---

## 📁 Struktur Proyek

```
LLFD_Task_Scheduling_Simulator/
├── 🚀 run.sh                              # Script otomasi build & run
├── 🐍 run_scalability_experiment.py       # Eksperimen skalabilitas 100–10.000 tasks
├── 📖 README.md
└── ☁️  cloudsim-plus-llfd/
    ├── pom.xml                            # Maven build file
    ├── dataset/
    │   ├── tasks.csv                      # 100 task dari Google Trace
    │   ├── prepare_google_trace.py        # Script transformasi dataset
    │   └── source_metadata.txt
    ├── results/
    │   ├── scalability_results.csv        # Rekap data 100–10.000 tasks
    │   ├── graph_combined.png             # Grafik gabungan semua metrik
    │   ├── graph_makespan.png
    │   ├── graph_energy.png
    │   ├── graph_throughput.png
    │   ├── graph_avg_util.png
    │   └── graph_improvement.png
    └── src/main/java/id/its/cloudtaskscheduling/
        └── LLFDCloudTaskScheduling.java
```

---

## ⚙️ Persyaratan

| Tools | Versi Minimum |
|-------|:-------------:|
| ☕ JDK | 17+ |
| 🔨 Apache Maven | 3.6+ |
| 🐍 Python | 3.8+ (`matplotlib`, `numpy`) |
| 🐚 Bash | Git Bash / WSL (Windows) |

---

## 🚀 Cara Menjalankan

### 1. Simulasi Utama (CloudSim Plus)

```bash
# Jalankan semua dari root repository
./run.sh
```

Script secara otomatis:
1. Masuk ke folder `cloudsim-plus-llfd`
2. Menjalankan `mvn compile exec:java` → simulasi LLFD
3. Menjalankan FCFS baseline
4. Menyimpan semua output ke `results/run.log`

**Atau jalankan manual:**
```bash
cd cloudsim-plus-llfd

# LLFD
mvn compile exec:java

# FCFS Baseline
mvn exec:java -Dexec.args="fcfs"
```

### 2. Eksperimen Skalabilitas (Python)

```bash
python run_scalability_experiment.py
```

Otomatis menghasilkan grafik dan CSV di `cloudsim-plus-llfd/results/` untuk rentang **100 hingga 10.000 tasks**.

---

## 📊 Hasil & Evaluasi

### ✅ Keunggulan LLFD

> LLFD secara konsisten unggul dalam **makespan**, **efisiensi energi**, **utilisasi VM**, dan **throughput** dibanding FCFS — bahkan di skala 10.000 task, dengan kompleksitas rendah **O(n log n)** yang cocok untuk lingkungan real-time.

---

### 🔬 Hasil Single Run (100 Tasks · Google Cluster Trace)

<div align="center">

| Metrik | FCFS Baseline | LLFD | Peningkatan |
|:------:|:-------------:|:----:|:-----------:|
| Tasks Completed | 100 | 100 | — |
| ⏱️ Makespan | 75.007.485.970,87 s | **24.740.108.562,86 s** | 🟢 **~67% lebih cepat** |
| 🔋 Total Energy | 86.493.133.946.367 J | **55.417.843.180.810 J** | 🟢 **~36% lebih hemat** |
| 📈 Avg VM Util. | 39,91% | **100,00%** | 🟢 **Optimal (+60 pp)** |

</div>

---

### 📈 Evaluasi Skalabilitas (100 — 10.000 Tasks)

<div align="center">

| Tasks | MS LLFD (s) | MS FCFS (s) | Eff. Makespan | E LLFD (J) | E FCFS (J) | Eff. Energi | Util LLFD | Util FCFS | TP LLFD | TP FCFS |
|:-----:|:-----------:|:-----------:|:-------------:|:----------:|:----------:|:-----------:|:---------:|:---------:|:-------:|:-------:|
| **100** | 31.25 | 37.50 | +16.7% | 39,875 | 46,875 | +14.9% | 43.75% | 43.02% | 3.20 | 2.67 |
| **500** | 96.88 | 171.88 | +43.6% | 140,906 | 206,375 | +31.7% | 56.85% | 41.25% | 5.16 | 2.91 |
| **1.000** | 184.38 | 325.00 | +43.3% | 266,365 | 390,615 | +31.8% | 56.20% | 41.32% | 5.42 | 3.08 |
| **2.000** | 368.75 | 643.75 | +42.7% | 532,729 | 775,875 | +31.3% | 56.20% | 41.78% | 5.42 | 3.11 |
| **3.000** | 565.63 | 987.50 | +42.7% | 816,781 | 1,191,740 | +31.5% | 56.17% | 42.03% | 5.30 | 3.04 |
| **4.000** | 759.38 | 1,281.25 | +40.7% | 1,097,240 | 1,562,198 | +29.8% | 56.24% | 42.90% | 5.27 | 3.12 |
| **5.000** | 953.13 | 1,618.75 | +41.1% | 1,376,323 | 1,968,438 | +30.1% | 56.20% | 42.58% | 5.25 | 3.09 |
| **6.000** | 1,134.38 | 1,915.63 | +40.8% | 1,639,719 | 2,335,635 | +29.8% | 56.29% | 42.83% | 5.29 | 3.13 |
| **7.000** | 1,328.13 | 2,246.88 | +40.9% | 1,919,573 | 2,735,167 | +29.8% | 56.27% | 42.62% | 5.27 | 3.12 |
| **8.000** | 1,518.75 | 2,603.13 | +41.7% | 2,194,479 | 3,152,958 | +30.4% | 56.24% | 42.11% | 5.27 | 3.07 |
| **9.000** | 1,709.38 | 2,934.38 | +41.8% | 2,469,990 | 3,556,635 | +30.5% | 56.24% | 42.31% | 5.27 | 3.07 |
| **10.000** | **1,893.75** | **3,218.75** | **🟢 +41.2%** | **2,736,958** | **3,915,521** | **🟢 +30.1%** | **56.27%** | **42.69%** | **5.28** | **3.11** |

</div>

---

### 📉 Visualisasi Grafik Performa

#### Gabungan Semua Metrik
![LLFD vs FCFS — Scalability Analysis](cloudsim-plus-llfd/results/graph_combined.png)

<br/>

#### Makespan & Konsumsi Energi
| Makespan (Waktu Penyelesaian) | Konsumsi Energi (Joules) |
|:-----------------------------:|:------------------------:|
| ![Makespan](cloudsim-plus-llfd/results/graph_makespan.png) | ![Energi](cloudsim-plus-llfd/results/graph_energy.png) |

<br/>

#### Throughput & Utilisasi VM
| Throughput (Tasks/detik) | Rata-rata Utilisasi VM (%) |
|:------------------------:|:--------------------------:|
| ![Throughput](cloudsim-plus-llfd/results/graph_throughput.png) | ![Utilisasi VM](cloudsim-plus-llfd/results/graph_avg_util.png) |

<br/>

#### Persentase Peningkatan LLFD vs FCFS
![Persentase Peningkatan](cloudsim-plus-llfd/results/graph_improvement.png)

---

## 📝 Format Output

<details>
<summary><b>📄 results/run.log</b> — Log lengkap simulasi</summary>

```
[Dataset Load Log]     — setiap task yang dibaca dari CSV
[Host Creation Log]    — detail setiap host yang dibuat
[VM Creation Log]      — detail setiap VM beserta host target
[Cloudlet Log]         — detail setiap cloudlet
[LLFD Mapping Log]     — assignment task -> VM + accumulated load
[VM Load Summary]      — distribusi beban per VM setelah scheduling
[Execution Results]    — waktu mulai, selesai, dan durasi per task
[Performance Metrics]  — makespan, energi, utilisasi, throughput
[FCFS Baseline]        — perbandingan dengan scheduling FCFS
```

</details>

<details>
<summary><b>📊 results/llfd_mapping.csv</b></summary>

```csv
cloudletIndex,vmId,lengthMI,startTime,finishTime,execTime,status
0,6,31250,0.1000,12.6000,12.5000,SUCCESS
...
```

</details>

<details>
<summary><b>📋 results/metrics.txt</b></summary>

```
Tasks Completed : 100
Makespan        : xx.xxxx s
Total Energy    : xx.xxxx J
Avg VM Util.    : xx.xx %
Throughput      : x.xxxxxx tasks/s
```

</details>

---

## 📜 Lisensi Dataset

Google Cluster Workload Trace Version 1 dirilis di bawah lisensi
[![CC BY](https://img.shields.io/badge/License-CC%20BY%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by/4.0/)

Lihat `cloudsim-plus-llfd/dataset/source_metadata.txt` untuk detail lengkap.

---

<div align="center">

Made with ☁️ by **Kelompok 4 — SOKA (A)**

</div>
