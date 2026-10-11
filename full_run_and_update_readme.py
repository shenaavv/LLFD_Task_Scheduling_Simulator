#!/usr/bin/env python3
"""Run LLFD & FCFS simulations for 100, 500, 1000 tasks three times each,
collect metrics, generate comparison plots, and embed them into README.

This script assumes Maven, Java, and Python (with pandas, matplotlib, seaborn)
are installed and available in the PATH.
"""

import subprocess
import pathlib
import shutil
import csv
import re
import sys

# ---------- Configuration ----------
ROOT = pathlib.Path(__file__).resolve().parent
JAVA_DIR = ROOT / "cloudsim-plus-llfd"
RESULTS_DIR = JAVA_DIR / "results"
DATASET_DIR = JAVA_DIR / "dataset"
DATASETS = ["tasks_100.csv", "tasks_500.csv", "tasks_1000.csv"]
REPEAT = 3

# Ensure results folder exists
RESULTS_DIR.mkdir(parents=True, exist_ok=True)

# Helper to run a Maven exec command
def run_maven(exec_args: str):
    # Explicit Maven executable to avoid PATH issues on Windows
    mvn_path = r"C:\\maven\\apache-maven-3.9.16\\bin\\mvn.cmd"
    # Build command: include compile phase and pass args as a single property
    cmd = [mvn_path, "-q", "-f", str(JAVA_DIR / "pom.xml"), "compile", "exec:java", f"-Dexec.args={exec_args}"]
    print("Running:", " ".join(cmd))
    result = subprocess.run(cmd, cwd=str(JAVA_DIR), capture_output=True, text=True)
    if result.returncode != 0:
        print("ERROR: command failed", result.stderr, file=sys.stderr)
        sys.exit(1)
    return result.stdout

run_id = 0
records = []  # list of dicts for CSV

for ds in DATASETS:
    for i in range(1, REPEAT + 1):
        run_id += 1
        print(f"=== Run {run_id} | Dataset {ds} | Iteration {i} ===")
        # ---- LLFD ----
        run_maven(f"llfd dataset/{ds}")
        # copy metric files
        llfd_metric_src = RESULTS_DIR / "metrics.txt"
        llfd_metric_dest = RESULTS_DIR / f"metrics_{run_id}_llfd.txt"
        shutil.copy(llfd_metric_src, llfd_metric_dest)
        # ---- FCFS ----
        run_maven(f"fcfs dataset/{ds}")
        fcfs_metric_src = RESULTS_DIR / "fcfs_metrics.txt"
        fcfs_metric_dest = RESULTS_DIR / f"metrics_{run_id}_fcfs.txt"
        shutil.copy(fcfs_metric_src, fcfs_metric_dest)
        # ---- Parse and store metrics ----
        def parse_metric_file(path: pathlib.Path):
            metrics = {}
            with path.open() as f:
                for line in f:
                    m = re.match(r"\s*([^:]+):\s*([\d\.]+)", line)
                    if m:
                        key = m.group(1).strip().lower().replace(' ', '_')
                        metrics[key] = float(m.group(2))
            return metrics
        llfd_metrics = parse_metric_file(llfd_metric_dest)
        fcfs_metrics = parse_metric_file(fcfs_metric_dest)
        # store rows
        for alg, met in [("llfd", llfd_metrics), ("fcfs", fcfs_metrics)]:
            records.append({
                "run_id": run_id,
                "dataset": ds,
                "algorithm": alg,
                "makespan": met.get("makespan"),
                "energy": met.get("total_energy"),
                "util": met.get("avg_vm_util."),
                "throughput": met.get("throughput")
            })

# ---------- Write aggregated CSV ----------
import csv
csv_path = RESULTS_DIR / "all_metrics.csv"
with csv_path.open('w', newline='') as f:
    writer = csv.DictWriter(f, fieldnames=["run_id", "dataset", "algorithm", "makespan", "energy", "util", "throughput"])
    writer.writeheader()
    writer.writerows(records)
print("Aggregated metrics written to", csv_path)

# ---------- Generate plots ----------
import csv
import matplotlib.pyplot as plt
# seaborn import removed (not used)

# Process CSV without pandas
from collections import defaultdict
metrics_data = defaultdict(lambda: defaultdict(list))  # {(dataset, algorithm): {metric: [values]}}
with open(csv_path, newline='') as f:
    reader = csv.DictReader(f)
    for row in reader:
        key = (row['dataset'], row['algorithm'])
        for metric in ['makespan', 'energy', 'util', 'throughput']:
            val = row[metric]
            if val:
                metrics_data[key][metric].append(float(val))

summary = []
for (dataset, algorithm), metric_vals in metrics_data.items():
    avg_row = {
        'dataset': dataset,
        'algorithm': algorithm,
        'makespan': sum(metric_vals['makespan']) / len(metric_vals['makespan']),
        'energy': sum(metric_vals['energy']) / len(metric_vals['energy']),
        'util': sum(metric_vals['util']) / len(metric_vals['util']),
        'throughput': sum(metric_vals['throughput']) / len(metric_vals['throughput'])
    }
    summary.append(avg_row)

def make_plot(metric_key, ylabel, filename):
    # Gather unique datasets and algorithms
    datasets = sorted({row['dataset'] for row in summary})
    algos = sorted({row['algorithm'] for row in summary})
    width = 0.2
    import numpy as np
    x = np.arange(len(datasets))
    plt.figure(figsize=(8,5))
    for i, algo in enumerate(algos):
        values = [next(row[metric_key] for row in summary if row['dataset']==ds and row['algorithm']==algo) for ds in datasets]
        plt.bar(x + i*width, values, width, label=algo)
    plt.xlabel('Dataset (number of tasks)')
    plt.ylabel(ylabel)
    plt.xticks(x + width*(len(algos)-1)/2, datasets)
    plt.title(f'Comparison of {ylabel} (average of {REPEAT} runs)')
    plt.legend()
    plt.tight_layout()
    out_path = RESULTS_DIR / filename
    plt.savefig(out_path, dpi=150)
    plt.close()
    print('Plot saved to', out_path)
# removed stray except block (error handling not needed as plotting is straightforward)

make_plot('makespan', 'Makespan (seconds)', 'plot_makespan.png')
make_plot('energy', 'Total Energy (Joules)', 'plot_energy.png')
make_plot('util', 'Avg VM Utilization (%)', 'plot_util.png')
make_plot('throughput', 'Throughput (tasks/s)', 'plot_throughput.png')

# ---------- Update README ----------
readme_path = ROOT / "README.md"
with readme_path.open('r', encoding='utf-8') as f:
    content = f.read()

# Insert a section before the end (or replace existing marker)
section_header = "\n## Perbandingan Grafik (Rata‑rata 3 Run)\n"
image_md = """
### Makespan\n![Makespan]({{ml}}/cloudsim-plus-llfd/results/plot_makespan.png)\n\
### Total Energy\n![Energy]({{ml}}/cloudsim-plus-llfd/results/plot_energy.png)\n\
### Avg VM Utilization\n![Utilization]({{ml}}/cloudsim-plus-llfd/results/plot_util.png)\n\
### Throughput\n![Throughput]({{ml}}/cloudsim-plus-llfd/results/plot_throughput.png)\n"""

# Use a placeholder token {{ml}} for repository root path (GitHub will resolve relative links)
new_section = section_header + image_md + "\n"
# If a previous auto‑generated section exists, replace it; otherwise append.
if "## Perbandingan Grafik" in content:
    # simple replace everything from that header onward
    start = content.find("## Perbandingan Grafik")
    content = content[:start] + new_section
else:
    content = content + new_section

with readme_path.open('w', encoding='utf-8') as f:
    f.write(content)
print('README updated with plot images.')

# End of script
