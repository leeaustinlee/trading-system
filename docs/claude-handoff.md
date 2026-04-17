# Claude 開發接手手冊（Trading System）

## 1. 目的
這份文件給 Claude 快速接手目前 Java 專案開發，避免重複探索與偏離規範。

## 2. 必讀順序（先讀再動手）
1. `docs/spec.md`（含 0.x 進度區塊）
2. `docs/api-spec.md`
3. `docs/architecture.md`
4. `src/main/resources/application-local.yml`
5. `sql/V1__init_schema.sql`（確認欄位）

若規則有衝突，以 `docs/spec.md` 為準。

## 3. 當前環境
- 專案路徑：`/mnt/d/ai/stock/trading-system`
- Java：17
- Maven：可用
- DB：MySQL `localhost:3330`
- Schema：`trading_system`
- 帳密：`root / HKtv2014`

## 4. 一鍵啟動與驗證
### 4.1 載入本地測資
```bash
./scripts/load-local-seed.sh
```

### 4.2 啟動
```bash
./scripts/run-local.sh
```

### 4.3 最小驗證 API
- `GET /api/dashboard/current`
- `GET /api/candidates/current`
- `GET /api/decisions/current`
- `GET /api/monitor/history`
- `GET /api/notifications?limit=20`
- `GET /api/pnl/summary`

## 5. 已完成範圍（不要重做）
- Phase 1：
- Spring Boot 骨架
- MySQL migration（V1~V3）
- 核心 Entity/Repository（含市場、狀態、通知）
- Phase 2：
- `MarketGateEngine`
- `HourlyGateEngine`
- `MonitorDecisionEngine`
- `FinalDecisionEngine`
- Scheduler skeleton：
- `HourlyIntradayGateJob`
- `FiveMinuteMonitorJob`
- `FinalDecision0930Job`
- 落表：
- `hourly_gate_decision`
- `monitor_decision`
- `final_decision`
- `scheduler_execution_log`
- API 已有：
- market / candidates / decisions / monitor / notifications / positions / pnl 主要 read/write 入口

## 6. 下一步優先任務（建議）
1. 補齊整合測試（DB + scheduler + API 端到端），至少覆蓋 `FinalDecision0930Job`、`HourlyIntradayGateJob`、`OpenDataPrepJob`
2. 完成 TAIFEX Open API end-to-end 驗證（實際呼叫 + 欄位映射校驗 + fallback 記錄）
3. 完成 LINE token 實機驗證（`trading.line.token`）並確認 cooldown/事件升級通知
4. 完成 `AiClaudeClient` 實機驗證（`trading.ai.claude.api-key`）並驗證 `ai_research_log` 寫入
5. 補上 `chasedHighEntry` 推算邏輯（依進場價與當日高點比對），並新增回歸測試

## 7. 重要程式入口
- 決策 API：`src/main/java/com/austin/trading/controller/DecisionController.java`
- Dashboard API：`src/main/java/com/austin/trading/controller/DashboardController.java`
- 候選整合：`src/main/java/com/austin/trading/service/CandidateScanService.java`
- 最終決策流程：`src/main/java/com/austin/trading/service/FinalDecisionService.java`
- 排程：`src/main/java/com/austin/trading/scheduler/`

## 8. 開發規範（務必遵守）
- 每次改動後執行：
```bash
mvn -Dmaven.repo.local=/tmp/m2 test -q
```
- 不要破壞既有 API 路徑與欄位名稱。
- 新增 endpoint 時同步更新：
- `docs/api-spec.md`
- `docs/spec.md` 的 0.x 進度區塊
- 優先讓功能「可跑可測」，再優化抽象。

## 9. 交付前檢查清單
- [ ] 編譯與測試通過
- [ ] migration 與 entity 欄位一致
- [ ] API 文件已更新
- [ ] `docs/spec.md` 進度已更新
- [ ] 本地啟動可驗證主要 API

## 10. 注意事項
- local scheduler 在 `application-local.yml` 已開啟部分任務；若要純 API 開發可先關閉。
- 如果 DB 無資料，先執行 `./scripts/load-local-seed.sh`。
- 若改動交易規則，務必先更新 `docs/spec.md` 再改程式。
