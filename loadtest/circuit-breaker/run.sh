#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
LABEL="${1:?usage: run.sh <label>}"
OUT_DIR="$ROOT/build/loadtest/$LABEL"
mkdir -p "$OUT_DIR"

python3 -I "$HERE/seed.py" --members "${MEMBERS:-150}"

"$HERE/sample-threads.sh" "$OUT_DIR/threads.csv" &
SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null || true' EXIT
sleep 2

cd "$ROOT/k6"
K6_CSV_TIME_FORMAT=unix_milli k6 run \
  --out "csv=$OUT_DIR/k6.csv" \
  --summary-export "$OUT_DIR/summary.json" \
  -e RUN_ID="$LABEL" \
  -e TOKENS_FILE="$ROOT/build/loadtest/paddle-test-users.json" \
  paddle-slow-upstream.js | tee "$OUT_DIR/k6-stdout.txt"

sleep 2
kill $SAMPLER 2>/dev/null || true
python3 -I "$HERE/analyze.py" "$OUT_DIR"
