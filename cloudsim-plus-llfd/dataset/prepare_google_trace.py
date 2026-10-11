#!/usr/bin/env python3
"""Convert task records from the official Google Cluster Trace v1."""

import csv
import gzip
import math
from pathlib import Path


DATASET_DIR = Path(__file__).resolve().parent
SOURCE = DATASET_DIR / "google-cluster-data-1.csv.gz"
TASK_LIMITS = [100, 500, 1000]
MAX_LIMIT = max(TASK_LIMITS)


def convert_record(cores_str, mem_str, job_type_str, task_id):
    normalized_cores = float(cores_str)
    normalized_memory = float(mem_str)

    # The v1 trace exposes normalized demand, not MI or absolute RAM.
    pes = max(1, min(4, math.ceil(normalized_cores * 32)))
    length_mi = max(1, round(normalized_cores * 1_000_000))
    ram_mb = max(1024, round(normalized_memory * 64 * 1024))

    return {
        "taskId": task_id,
        "lengthMI": length_mi,
        "pes": pes,
        "ramMB": ram_mb,
        "priority": int(job_type_str) if job_type_str.isdigit() else 0,
    }


def main():
    if not SOURCE.exists():
        raise FileNotFoundError(
            f"Raw trace not found: {SOURCE}\n"
            "Download from: "
            "http://commondatastorage.googleapis.com/clusterdata-misc/google-cluster-data-1.csv.gz"
        )

    seen_task_ids = set()
    rows = []

    with gzip.open(SOURCE, "rt", encoding="utf-8") as f:
        # Check header
        for line in f:
            parts = line.strip().split()
            if not parts or parts[0] == "Time":
                continue
            if len(parts) < 6:
                continue

            tid = parts[2]
            job_type = parts[3]
            cores_str = parts[4]
            mem_str = parts[5]

            try:
                cores = float(cores_str)
            except ValueError:
                continue

            if cores <= 0:
                continue

            if tid in seen_task_ids:
                continue

            seen_task_ids.add(tid)
            rows.append(convert_record(cores_str, mem_str, job_type, len(rows) + 1))
            if len(rows) >= MAX_LIMIT:
                break

    for limit in TASK_LIMITS:
        subset = rows[:limit]
        out_path = DATASET_DIR / f"tasks_{limit}.csv"
        with open(out_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=["taskId", "lengthMI", "pes", "ramMB", "priority"])
            writer.writeheader()
            writer.writerows(subset)
        print(f"Written {len(subset)} tasks to {out_path}")

    # Also keep tasks.csv as default 100 tasks for backward compatibility
    default_out = DATASET_DIR / "tasks.csv"
    with open(default_out, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["taskId", "lengthMI", "pes", "ramMB", "priority"])
        writer.writeheader()
        writer.writerows(rows[:100])
    print(f"Written 100 tasks to {default_out} (default)")


if __name__ == "__main__":
    main()
