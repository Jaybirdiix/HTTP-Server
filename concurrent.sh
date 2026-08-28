#!/usr/bin/env bash
set -euo pipefail

HOST="${1:-127.0.0.1}"
PORT="${2:-6789}"
CONNS="${3:-30}"
DURATION_SECS="${4:-8}"     # how long to run the experiment
POLL_MS="${5:-10}"          # poll interval in ms (10ms default)

BASE="http://${HOST}:${PORT}"
tmpdir="$(mktemp -d)"
poll_log="${tmpdir}/poll.log"

cleanup() {
  # kill all background jobs we started
  jobs -pr | xargs -r kill >/dev/null 2>&1 || true
  wait 2>/dev/null || true
  rm -rf "$tmpdir"
}
trap cleanup EXIT

echo "Starting /load poller in background (every ${POLL_MS}ms) ..."
(
  end_time=$(( $(date +%s) + DURATION_SECS ))
  saw_503=0
  while [[ "$(date +%s)" -lt "$end_time" ]]; do
    code="$(curl -sS --no-keepalive -o /dev/null -w "%{http_code}" "${BASE}/load" || true)"
    ts="$(date +%H:%M:%S.%3N)"
    echo "${ts} /load -> ${code}" >> "$poll_log"
    if [[ "$code" == "503" ]]; then
      saw_503=1
      echo "${ts} ✅ SAW 503" >> "$poll_log"
      break
    fi
    # sleep in ms
    python3 - <<PY
import time
time.sleep(${POLL_MS}/1000.0)
PY
  done

  if [[ "$saw_503" -eq 0 ]]; then
    echo "$(date +%H:%M:%S.%3N) ❌ never saw 503" >> "$poll_log"
  fi
) &

poller_pid=$!

echo "Opening ${CONNS} partial connections (background) ..."
for i in $(seq 1 "$CONNS"); do
  (
    # partial headers: never send the final \r\n\r\n
    printf "GET / HTTP/1.1\r\nHost: localhost\r\nX-Conn: %02d\r\n" "$i"
    sleep "$DURATION_SECS"
  ) | nc "$HOST" "$PORT" >/dev/null 2>&1 &
done

# wait for poller to finish, then print results
wait "$poller_pid" || true

echo
echo "=== /load poll log (last 50 lines) ==="
tail -n 50 "$poll_log"
