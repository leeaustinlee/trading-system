#!/usr/bin/env bash
#
# 一鍵恢復 + 補跑漏掉的盤後排程
#
# 使用情境：Java App 因為某個原因關掉超過數小時/數天，
#         今天 18:10 / 18:30 等 step 全部沒跑，需要：
#         1. 先把 Java App 重新拉起來
#         2. 等 health 變 UP
#         3. 依序 force 觸發今天漏掉的 step
#         4. 確認 orchestration 狀態
#
# 用法：
#   cd /mnt/d/ai/stock/trading-system
#   ./scripts/recover-and-catchup.sh
#
# 預設只補今天的盤後（postmarket-data-prep, postmarket-analysis,
# watchlist-refresh, t86-data-prep, tomorrow-plan）。如要改補別的 step，
# 編輯下方 STEPS_TO_RECOVER 陣列。
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR="$PROJECT_DIR/target/trading-system-0.0.1-SNAPSHOT.jar"
LOG="$PROJECT_DIR/logs/app.log"
PROFILE="${SPRING_PROFILES_ACTIVE:-local}"
HEALTH_URL="http://localhost:8080/actuator/health"
TRIGGER_BASE="http://localhost:8080/api/scheduler/trigger"

# 要補的 step，按時序排列（早 -> 晚）
STEPS_TO_RECOVER=(
  "postmarket-data-prep:30"
  "postmarket:30"
  "watchlist-refresh:20"
  "t86-data-prep:60"
  "tomorrow-plan:30"
)

cd "$PROJECT_DIR"

echo "================================================================"
echo "[recover] $(date '+%F %T') 啟動恢復流程"
echo "[recover] PROJECT_DIR = $PROJECT_DIR"
echo "[recover] PROFILE     = $PROFILE"
echo "[recover] JAR         = $JAR"
echo "================================================================"

# -----------------------------------------------------------------------------
# Step 1: 確認 jar 存在
# -----------------------------------------------------------------------------
if [[ ! -f "$JAR" ]]; then
  echo "[recover] ERROR: 找不到 jar，請先 mvn package -DskipTests"
  exit 1
fi

# -----------------------------------------------------------------------------
# Step 2: 殺掉舊 process（如果還活著）
# -----------------------------------------------------------------------------
OLD_PID=$(pgrep -f 'trading-system-0.0.1-SNAPSHOT.jar' | head -1 || true)
if [[ -n "$OLD_PID" ]]; then
  echo "[recover] 偵測到舊 process pid=$OLD_PID，先 graceful kill"
  kill "$OLD_PID" || true
  for i in {1..30}; do
    if ! kill -0 "$OLD_PID" 2>/dev/null; then
      echo "[recover] 舊 process 已結束"
      break
    fi
    sleep 1
  done
  if kill -0 "$OLD_PID" 2>/dev/null; then
    echo "[recover] graceful timeout，改用 SIGKILL"
    kill -9 "$OLD_PID" || true
    sleep 2
  fi
else
  echo "[recover] 沒有舊 process（符合預期，因為 app 已關閉）"
fi

# -----------------------------------------------------------------------------
# Step 3: 載 .env
# -----------------------------------------------------------------------------
if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
  echo "[recover] .env 已載入（LINE_ENABLED=${LINE_ENABLED:-unset}）"
else
  echo "[recover] 警告：.env 不存在，LINE 與 DB 將使用預設值"
fi

# -----------------------------------------------------------------------------
# Step 4: 啟動
# -----------------------------------------------------------------------------
mkdir -p "$PROJECT_DIR/logs"
echo "[recover] 啟動 Java App..."
nohup java -Xms512m -Xmx1536m \
  -Dspring.profiles.active="$PROFILE" \
  -jar "$JAR" >> "$LOG" 2>&1 &
NEW_PID=$!
disown
echo "[recover] 新 process pid=$NEW_PID，log=$LOG"

# -----------------------------------------------------------------------------
# Step 5: 等 Spring Boot 啟動完成（最多 120 秒）
# -----------------------------------------------------------------------------
echo "[recover] 等待 Spring Boot 啟動..."
UP=0
for i in {1..60}; do
  if curl -sf -m 3 "$HEALTH_URL" > /dev/null 2>&1; then
    UP=1
    echo "[recover] ✓ App UP @ $(date '+%T')（耗時約 $((i*2)) 秒）"
    break
  fi
  sleep 2
done

if [[ "$UP" -ne 1 ]]; then
  echo "[recover] ERROR: 啟動 timeout，最後 30 行 log："
  tail -30 "$LOG"
  exit 1
fi

# -----------------------------------------------------------------------------
# Step 6: 依序補跑漏掉的 step
# -----------------------------------------------------------------------------
echo ""
echo "================================================================"
echo "[recover] 開始補跑漏掉的 step（force=true 覆寫保護）"
echo "================================================================"

FAILED_STEPS=()
for spec in "${STEPS_TO_RECOVER[@]}"; do
  STEP="${spec%%:*}"
  WAIT_SEC="${spec##*:}"
  echo ""
  echo "[recover] >>> 觸發 $STEP (等待 ${WAIT_SEC}s)..."
  RESPONSE=$(curl -sS -X POST "$TRIGGER_BASE/$STEP?force=true" -m 60 2>&1 || echo "CURL_FAIL")
  echo "[recover]     回應: $RESPONSE"
  if [[ "$RESPONSE" == *"CURL_FAIL"* || "$RESPONSE" == *"error"* || "$RESPONSE" == *"500"* ]]; then
    echo "[recover]     ⚠ $STEP 看起來失敗，繼續下一個"
    FAILED_STEPS+=("$STEP")
  fi
  sleep "$WAIT_SEC"
done

# -----------------------------------------------------------------------------
# Step 7: 印今日 orchestration 狀態
# -----------------------------------------------------------------------------
echo ""
echo "================================================================"
echo "[recover] 今日 orchestration 狀態（GET /api/orchestration/today）"
echo "================================================================"
curl -sS "http://localhost:8080/api/orchestration/today" | (jq . 2>/dev/null || cat)

echo ""
echo "================================================================"
echo "[recover] 今日 ai_task 狀態（GET /api/ai/tasks）"
echo "================================================================"
curl -sS "http://localhost:8080/api/ai/tasks" | (jq '.[] | {taskType, status, tradingDate, claudeDoneAt, codexDoneAt}' 2>/dev/null || cat)

echo ""
echo "================================================================"
echo "[recover] 完成 @ $(date '+%F %T')"
if [[ ${#FAILED_STEPS[@]} -gt 0 ]]; then
  echo "[recover] ⚠ 以下 step 觸發失敗（請手動查 log）："
  printf '         - %s\n' "${FAILED_STEPS[@]}"
  exit 2
fi
echo "[recover] ✓ 全部 step 已觸發完畢，請看上方 orchestration 狀態確認"
echo "================================================================"
