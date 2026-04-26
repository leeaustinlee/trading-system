#!/usr/bin/env bash
# Internal launcher used by Claude orchestration.
# Loads .env via python (avoids `&` parsing in `set -a; . ./.env`),
# kills stale mvn / TradingApplication, then nohup setsid relaunches.
set -uo pipefail
cd /mnt/d/ai/stock/trading-system

# kill stale
for pid in $(pgrep -f 'TradingApplication\|spring-boot:run' 2>/dev/null); do
  kill -TERM "$pid" 2>/dev/null || true
done
sleep 3
for pid in $(pgrep -f 'TradingApplication\|spring-boot:run' 2>/dev/null); do
  kill -KILL "$pid" 2>/dev/null || true
done

eval "$(python3 - << 'PY'
import shlex
with open('.env') as f:
    for line in f:
        s = line.strip()
        if not s or s.startswith('#') or '=' not in s:
            continue
        k, v = s.split('=', 1)
        print(f'export {k}={shlex.quote(v)}')
PY
)"

if [ -z "${DB_URL:-}" ]; then
  echo "ERROR: DB_URL empty" >&2
  exit 1
fi

: > /tmp/trading-system.log
nohup setsid mvn spring-boot:run \
  -Dspring-boot.run.profiles=prod \
  -Dmaven.repo.local=/tmp/m2 \
  > /tmp/trading-system.log 2>&1 < /dev/null &
disown
echo "launched pid=$!"
