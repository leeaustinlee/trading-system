#!/usr/bin/env bash
# 快速重啟 trading-system 本機 server（WSL）。
# 流程：kill 舊 process → mvn clean package → 背景啟動 → 等 "Started TradingApplication"
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$PROJECT_DIR/target/trading-system-0.0.1-SNAPSHOT.jar"
LOG="/tmp/trading.log"
PROFILE="${SPRING_PROFILES_ACTIVE:-local}"

cd "$PROJECT_DIR"

# 1) 停舊 process
OLD_PID=$(ps aux | grep 'trading-system-0.0.1-SNAPSHOT.jar' | grep -v grep | awk '{print $2}' | head -1 || true)
if [[ -n "$OLD_PID" ]]; then
  echo "[restart] killing pid $OLD_PID"
  kill "$OLD_PID"
  for i in {1..30}; do
    if ! ps -p "$OLD_PID" > /dev/null 2>&1; then break; fi
    sleep 1
  done
fi

# 2) build（略過測試以加速；要完整測試請自行 mvn verify）
echo "[restart] building..."
mvn -q clean package -DskipTests

# 3) 載 .env 並背景啟動
if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi

echo "[restart] starting (profile=$PROFILE, log=$LOG)..."
nohup java -Xms512m -Xmx1536m \
  -Dspring.profiles.active="$PROFILE" \
  -jar "$JAR" > "$LOG" 2>&1 &
disown
NEW_PID=$!
echo "[restart] pid=$NEW_PID"

# 4) 等 "Started TradingApplication"
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
