#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$PROJECT_DIR/.env"
BASE_URL="${BASE_URL:-http://localhost:8080}"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$ENV_FILE"
  set +a
fi

LINE_LIVE="${LINE_LIVE:-${LINE_ENABLED:-false}}"
CLAUDE_LIVE="${CLAUDE_LIVE:-${CLAUDE_ENABLED:-false}}"

if [ "$LINE_LIVE" = "true" ]; then
  if [ -z "${LINE_CHANNEL_ACCESS_TOKEN:-${LINE_TOKEN:-}}" ]; then
    echo "[probe-external] LINE_LIVE=true but LINE_CHANNEL_ACCESS_TOKEN is empty"
    exit 1
  fi
  if [ -z "${LINE_TO:-}" ]; then
    echo "[probe-external] LINE_LIVE=true but LINE_TO is empty"
    exit 1
  fi
fi
if [ "$CLAUDE_LIVE" = "true" ] && [ -z "${CLAUDE_API_KEY:-}" ]; then
  echo "[probe-external] CLAUDE_LIVE=true but CLAUDE_API_KEY is empty"
  exit 1
fi

URL="$BASE_URL/api/system/external/probe?liveLine=$LINE_LIVE&liveClaude=$CLAUDE_LIVE"
HISTORY_URL="$BASE_URL/api/system/external/probe/history?limit=5"

echo "[probe-external] probe: $URL"
curl -sS "$URL"
echo
echo "[probe-external] history: $HISTORY_URL"
curl -sS "$HISTORY_URL"
echo
