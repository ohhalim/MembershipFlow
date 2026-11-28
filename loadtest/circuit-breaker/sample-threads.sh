#!/usr/bin/env bash
set -uo pipefail

OUT="$1"
URL="${MANAGEMENT_URL:-http://localhost:8082}/actuator/prometheus"
echo "epoch_ms,busy_threads,current_threads,max_threads" > "$OUT"
while true; do
  now=$(python3 -c 'import time;print(int(time.time()*1000))')
  body=$(curl -s --max-time 1 "$URL")
  busy=$(echo "$body" | awk -F' ' '/^tomcat_threads_busy_threads\{name="http-nio-8081"/{print $2}')
  current=$(echo "$body" | awk -F' ' '/^tomcat_threads_current_threads\{name="http-nio-8081"/{print $2}')
  max=$(echo "$body" | awk -F' ' '/^tomcat_threads_config_max_threads\{name="http-nio-8081"/{print $2}')
  echo "$now,${busy:-},${current:-},${max:-}" >> "$OUT"
  sleep 1
done
