#!/bin/bash
# Helper to run trace_one.py for the 04-22 forensic cases.
set -eu

OUT_DIR="${1:-/tmp/wt-p01-trace/scripts/trace-out}"
mkdir -p "$OUT_DIR"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

for s in 6770 8028 2454; do
    python3 "$SCRIPT_DIR/trace_one.py" --symbol "$s" --date 2026-04-22 > "$OUT_DIR/${s}.txt"
    echo "wrote $OUT_DIR/${s}.txt ($(wc -l < "$OUT_DIR/${s}.txt") lines)"
done
