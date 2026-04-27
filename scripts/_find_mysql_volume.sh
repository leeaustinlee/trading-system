#!/usr/bin/env bash
# helper: find any docker volume containing a MySQL data dir
for v in $(ls /var/lib/docker/volumes/ 2>/dev/null | grep -v 'backingFs\|metadata'); do
  d="/var/lib/docker/volumes/$v/_data"
  if [ -f "$d/auto.cnf" ] || [ -d "$d/mysql" ]; then
    echo "MYSQL_VOLUME=$v"
    ls "$d" 2>/dev/null | head -8
    echo "---dbs---"
    ls "$d" 2>/dev/null | grep -vE '^(mysql|sys|performance_schema|information_schema|ibdata|ib_|auto|undo|#|mysqlx|client-|server-|public_|private_|ca|ca-|binlog|\.)' | head -10
    echo "==="
  fi
done
