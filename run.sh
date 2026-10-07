#!/usr/bin/env bash
# =============================================================================
# run.sh — LLFD Task Scheduling Simulator
# =============================================================================
# Builds and runs the CloudSim Plus LLFD simulation, then optionally runs the
# FCFS baseline for comparison.  All output is mirrored to results/run.log.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_DIR="$SCRIPT_DIR/cloudsim-plus-llfd"
LOG_FILE="$JAVA_DIR/results/run.log"

# Ensure results directory exists and clear previous log
mkdir -p "$(dirname "$LOG_FILE")"
: > "$LOG_FILE"

separator() {
    echo "=================================================" | tee -a "$LOG_FILE"
}

log() {
    echo "$*" | tee -a "$LOG_FILE"
}

separator
log " LLFD Task Scheduling Simulator"
log " Least-Loaded First-Fit Decreasing + CloudSim Plus"
separator
log ""
log " Log file : $LOG_FILE"
log " Project  : $JAVA_DIR"
log " Started  : $(date)"
log ""

# Move into the Maven project directory
cd "$JAVA_DIR" || { echo "[ERROR] Cannot enter $JAVA_DIR"; exit 1; }

# ----------------------------------------------------------------
# Phase 1: LLFD simulation
# ----------------------------------------------------------------
separator
log " PHASE 1 — LLFD Simulation"
separator

mvn -q compile exec:java -Dexec.args="" 2>&1 | tee -a "$LOG_FILE"
llfd_status="${PIPESTATUS[0]}"

if [ "$llfd_status" -ne 0 ]; then
    log ""
    log "[ERROR] LLFD simulation failed (exit $llfd_status)."
    exit "$llfd_status"
fi

log ""
log "[OK] LLFD simulation completed successfully."

# ----------------------------------------------------------------
# Phase 2: FCFS baseline
# ----------------------------------------------------------------
separator
log " PHASE 2 — FCFS Baseline Simulation"
separator

mvn -q exec:java -Dexec.args="fcfs" 2>&1 | tee -a "$LOG_FILE"
fcfs_status="${PIPESTATUS[0]}"

if [ "$fcfs_status" -ne 0 ]; then
    log ""
    log "[WARN] FCFS baseline failed (exit $fcfs_status). Continuing."
fi

log ""
log "[OK] FCFS baseline completed."

# ----------------------------------------------------------------
# Results and comparison summary
# ----------------------------------------------------------------
separator
log " RESULTS AND COMPARISON"
separator

print_metrics_file() {
    local label="$1"
    local file="$2"

    log ""
    log " $label"
    if [ -f "$file" ]; then
        while IFS= read -r line; do
            log "   $line"
        done < "$file"
    else
        log "   [UNAVAILABLE] $file"
    fi
}

metric_value() {
    local file="$1"
    local key="$2"

    awk -F: -v key="$key" '
        $1 == key {
            value = $2
            sub(/^[[:space:]]+/, "", value)
            match(value, /^[-+]?[0-9]+([.][0-9]+)?/)
            if (RSTART > 0) {
                print substr(value, RSTART, RLENGTH)
            }
            exit
        }
    ' "$file"
}

print_metrics_file "LLFD METRICS" "results/metrics.txt"
print_metrics_file "FCFS BASELINE METRICS" "results/fcfs_metrics.txt"

if [ -f "results/metrics.txt" ] && [ -f "results/fcfs_metrics.txt" ]; then
    llfd_makespan="$(metric_value results/metrics.txt "Makespan        ")"
    fcfs_makespan="$(metric_value results/fcfs_metrics.txt "Makespan        ")"
    llfd_energy="$(metric_value results/metrics.txt "Total Energy    ")"
    fcfs_energy="$(metric_value results/fcfs_metrics.txt "Total Energy    ")"
    llfd_util="$(metric_value results/metrics.txt "Avg VM Util.    ")"
    fcfs_util="$(metric_value results/fcfs_metrics.txt "Avg VM Util.    ")"
    llfd_throughput="$(metric_value results/metrics.txt "Throughput      ")"
    fcfs_throughput="$(metric_value results/fcfs_metrics.txt "Throughput      ")"

    log ""
    log " COMPARISON (LLFD vs FCFS)"
    printf " %-18s %22s %22s %22s\n" \
        "Metric" "LLFD" "FCFS" "LLFD vs FCFS" | tee -a "$LOG_FILE"
    printf " %-18s %22s %22s %22s\n" \
        "------------------" "----------------------" "----------------------" "----------------------" | tee -a "$LOG_FILE"
    printf " %-18s %22.4f %22.4f %22.2f%%\n" \
        "Makespan (s)" "$llfd_makespan" "$fcfs_makespan" \
        "$(awk -v l="$llfd_makespan" -v f="$fcfs_makespan" 'BEGIN { if (f == 0) print 0; else print (l - f) / f * 100 }')" | tee -a "$LOG_FILE"
    printf " %-18s %22.4f %22.4f %22.2f%%\n" \
        "Energy (J)" "$llfd_energy" "$fcfs_energy" \
        "$(awk -v l="$llfd_energy" -v f="$fcfs_energy" 'BEGIN { if (f == 0) print 0; else print (l - f) / f * 100 }')" | tee -a "$LOG_FILE"
    printf " %-18s %22.2f %22.2f %22.2f%%\n" \
        "Avg Utilization" "$llfd_util" "$fcfs_util" \
        "$(awk -v l="$llfd_util" -v f="$fcfs_util" 'BEGIN { if (f == 0) print 0; else print (l - f) / f * 100 }')" | tee -a "$LOG_FILE"
    printf " %-18s %22.6f %22.6f %22.2f%%\n" \
        "Throughput" "$llfd_throughput" "$fcfs_throughput" \
        "$(awk -v l="$llfd_throughput" -v f="$fcfs_throughput" 'BEGIN { if (f == 0) print 0; else print (l - f) / f * 100 }')" | tee -a "$LOG_FILE"
    log ""
    log " Persentase negatif pada makespan/energi berarti LLFD lebih baik dari FCFS."
fi

separator
log " RUN COMPLETE"
separator
log " Finished : $(date)"
log ""
log " Output files:"
log "   - results/run.log          (this log)"
log "   - results/llfd_mapping.csv (task→VM mapping)"
log "   - results/metrics.txt      (LLFD performance metrics)"
log "   - results/fcfs_metrics.txt (FCFS baseline metrics)"
separator
