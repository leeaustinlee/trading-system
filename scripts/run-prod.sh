#!/usr/bin/env bash
# 正式環境啟動腳本
# 前置：.env 中必須設定 DB / Claude，LINE 啟用時需設定 LINE_CHANNEL_ACCESS_TOKEN + LINE_TO
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

ENV_FILE="$PROJECT_DIR/.env"
if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ENV_FILE"
  set +a
else
  echo "[run-prod] 警告：找不到 .env，請確認環境變數已設定"
fi

# 必填欄位確認
: "${DB_URL:?DB_URL 未設定}"
: "${DB_USERNAME:?DB_USERNAME 未設定}"
: "${DB_PASSWORD:?DB_PASSWORD 未設定}"
: "${CLAUDE_API_KEY:?CLAUDE_API_KEY 未設定（正式環境必填）}"

if [ "${LINE_ENABLED:-true}" = "true" ]; then
  if [ -z "${LINE_CHANNEL_ACCESS_TOKEN:-${LINE_TOKEN:-}}" ]; then
    echo "LINE_CHANNEL_ACCESS_TOKEN 未設定（LINE_ENABLED=true 時必填）"
    exit 1
  fi
  : "${LINE_TO:?LINE_TO 未設定（LINE_ENABLED=true 時必填）}"
fi

echo "[run-prod] 使用 prod profile 啟動（Flyway 啟用，所有排程開啟）"
cd "$PROJECT_DIR"
mvn spring-boot:run \
  -Dspring-boot.run.profiles=prod \
  -Dmaven.repo.local=/tmp/m2
