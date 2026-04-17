# Trading Decision Platform

台股中短線交易決策平台（Java / Spring Boot 版）。

## Quick Start

1. 準備 Java 17 與 MySQL 8+
2. 建立 DB：`trading_system`（local MySQL port 預設 `3330`）
3. 以環境變數設定敏感資訊（可參考 `.env.example`）
4. 啟動：`./scripts/run-local.sh`

本地 UI Console：`http://localhost:8080/`

若要快速驗證 API，可在 `application-local.yml` 設定：
`trading.mock-data-loader.enabled: true`

本地 Phase2/3 測資可直接載入：
`./scripts/load-local-seed.sh`

## LINE 設定

所有 LINE 參數都由 `.env` 控制（勿寫死在程式）：

- `LINE_ENABLED=false|true`
- `LINE_CHANNEL_ACCESS_TOKEN=...`
- `LINE_TO=...`
- `LINE_PUSH_URL=https://api.line.me/v2/bot/message/push`

若 `LINE_ENABLED=true` 且 `LINE_CHANNEL_ACCESS_TOKEN` / `LINE_TO` 缺值，`run-local` / `run-prod` 會直接中止啟動。

外部探針驗證（服務啟動後）：
`./scripts/probe-external.sh`

## 目標

- 固化市場資料收集與決策流程
- 以 Java engine 控制規則與風控
- 以 LINE 事件通知降低盤中噪音
- 支援 UI/API 查詢當前狀態與歷史紀錄

詳細請見 `docs/`。
