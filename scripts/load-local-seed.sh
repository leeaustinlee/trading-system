#!/usr/bin/env bash
set -e
"/mnt/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" --default-character-set=utf8mb4 -h127.0.0.1 -P3330 -uroot -pHKtv2014 trading_system -e "SOURCE D:/ai/stock/trading-system/sql/local-seed-phase2.sql;"
echo "Local seed loaded into trading_system"
