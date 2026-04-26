#!/usr/bin/env bash
set -uo pipefail
cd /mnt/d/ai/stock/trading-system
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
echo "DB_URL=${DB_URL:0:40}"
echo "-- mvn 8s test --"
timeout 12 mvn spring-boot:run -Dspring-boot.run.profiles=prod -Dmaven.repo.local=/tmp/m2 < /dev/null 2>&1 | tail -20
echo "-- exit code: $? --"
