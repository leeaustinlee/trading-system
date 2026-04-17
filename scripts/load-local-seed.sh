#!/usr/bin/env bash
set -e

if [ -f ".env" ]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

MYSQL_BIN="${MYSQL_BIN:-/mnt/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3330}"
MYSQL_USER="${MYSQL_USER:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
MYSQL_DB="${MYSQL_DB:-trading_system}"
SEED_SQL_PATH="${SEED_SQL_PATH:-D:/ai/stock/trading-system/sql/local-seed-phase2.sql}"

PASS_ARG=()
if [ -n "$MYSQL_PASSWORD" ]; then
  PASS_ARG=(-p"$MYSQL_PASSWORD")
fi

"$MYSQL_BIN" --default-character-set=utf8mb4 \
  -h"$MYSQL_HOST" -P"$MYSQL_PORT" -u"$MYSQL_USER" "${PASS_ARG[@]}" "$MYSQL_DB" \
  -e "SOURCE $SEED_SQL_PATH;"

echo "Local seed loaded into $MYSQL_DB"
