# Trading Decision Platform

台股中短線交易決策平台（Java / Spring Boot 版）。

## Quick Start

1. 準備 Java 17 與 MySQL 8+
2. 建立 DB：`trading_system`（local MySQL port 預設 `3330`）
3. 設定 `src/main/resources/application-local.yml`
4. 啟動：`./scripts/run-local.sh`

本地 UI Console：`http://localhost:8080/`

若要快速驗證 API，可在 `application-local.yml` 設定：
`trading.mock-data-loader.enabled: true`

本地 Phase2/3 測資可直接載入：
`./scripts/load-local-seed.sh`

## 目標

- 固化市場資料收集與決策流程
- 以 Java engine 控制規則與風控
- 以 LINE 事件通知降低盤中噪音
- 支援 UI/API 查詢當前狀態與歷史紀錄

詳細請見 `docs/`。
