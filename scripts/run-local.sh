#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

if [ -f "$PROJECT_DIR/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$PROJECT_DIR/.env"
  set +a
fi

if [ "${LINE_ENABLED:-false}" = "true" ] && [ -z "${LINE_TOKEN:-}" ]; then
  echo "[run-local] 錯誤：LINE_ENABLED=true 但 LINE_TOKEN 未設定"
  exit 1
fi

cd "$PROJECT_DIR"
mvn spring-boot:run -Dspring-boot.run.profiles=local
