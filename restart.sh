#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$PROJECT_DIR/target/trading-system-0.0.1-SNAPSHOT.jar"
LOG="/tmp/trading.log"
PROFILE="${SPRING_PROFILES_ACTIVE:-local}"

cd "$PROJECT_DIR"

# 1) Stop the previous app process if it is still running.
OLD_PID=$(ps aux | grep 'trading-system-0.0.1-SNAPSHOT.jar' | grep -v grep | awk '{print $2}' | head -1 || true)
if [[ -n "$OLD_PID" ]]; then
  echo "[restart] killing pid $OLD_PID"
  kill "$OLD_PID"
  for i in {1..30}; do
    if ! ps -p "$OLD_PID" > /dev/null 2>&1; then
      break
    fi
    sleep 1
  done
fi

# 2) Rebuild without `clean` to avoid Windows/WSL file lock failures in target/.
echo "[restart] building..."
mvn -q package -DskipTests

# 3) Load local environment variables if present.
if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi

# 4) Start the packaged jar in the background.
echo "[restart] starting (profile=$PROFILE, log=$LOG)..."
nohup java -Xms512m -Xmx1536m \
  -Dspring.profiles.active="$PROFILE" \
  -jar "$JAR" > "$LOG" 2>&1 &
disown
NEW_PID=$!
echo "[restart] pid=$NEW_PID"

# 5) Wait until Spring Boot startup is visible in the log.
for i in {1..60}; do
  if grep -q "Started TradingApplication" "$LOG" 2>/dev/null; then
    echo "[restart] UP @ $(grep -m1 'Started TradingApplication' "$LOG" | awk '{print $1}')"
    echo "[restart] health: $(curl -sf http://localhost:8080/actuator/health 2>/dev/null || echo 'not ready')"
    echo "[restart] tail log: tail -f $LOG"
    exit 0
  fi
  sleep 2
done

echo "[restart] ERROR: timeout waiting for startup. See $LOG"
tail -20 "$LOG"
exit 1
