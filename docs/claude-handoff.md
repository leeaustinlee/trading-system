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

或 IntelliJ Run（設 Active profiles = `local`）。

### 4.3 最小驗證 API
- `GET /api/dashboard/current`
- `GET /api/candidates/current`
- `GET /api/decisions/current`
- `GET /api/monitor/history`
- `GET /api/notifications?limit=20`
- `GET /api/pnl/summary`

## 5. 已完成範圍（不要重做）

**Phase 1 — 骨架**
- Spring Boot 專案、MySQL migration V1~V3、核心 Entity/Repository

**Phase 2 — 決策引擎**
- `MarketGateEngine`、`HourlyGateEngine`、`MonitorDecisionEngine`、`FinalDecisionEngine`
- Scheduler：`HourlyIntradayGateJob`、`FiveMinuteMonitorJob`、`FinalDecision0930Job`
- 落表：`hourly_gate_decision`、`monitor_decision`、`final_decision`、`scheduler_execution_log`

**Phase 3 — 估值 / 外部 client / 全排程**
- `StockEvaluationEngine`、`PositionSizingEngine`、`StopLossTakeProfitEngine`、`ReviewScoringEngine`、`ChasedHighEntryEngine`
- `PATCH /api/positions/{id}/close`：自動計算 realizedPnl（V4 migration）
- LINE 通知：`LineSender`（LINE Push API）、`LineMessageBuilder`、`LineTemplateService`
- 外部 client：`TwseMisClient`、`TwseInstitutionalClient`、`TaifexClient`、`TpexClient`、`MarketBreadthClient`
- 全 13 個排程 Job（全部可 flag 開關）
- V4 migration：`close_price`、`realized_pnl`（已確認存在 DB）

**Phase 4 — UI**
- 七頁分頁 SPA（`src/main/resources/static/index.html`）：總覽 / 候選股 / 持倉 / 損益 / 決策歷史 / AI 研究 / 系統
- 60 秒自動刷新當前頁

**Phase 5 — AI Adapter**
- `AiClaudeClient`（Anthropic Messages API）、`AiCodexClient`
- Prompt builders（5 種）、`AiFacade`、`AiResearchService`
- Migration V5：`ai_research_log`；V6：`external_probe_log`
- `SystemController`：`/api/system/external/probe`（dry-run/live）、probe history、migration health
- `MigrationHealthService`：Flyway 停用時正確回報 `flyway.disabled=true`

**測試**
- 62 tests pass，4 skipped（TAIFEX live，需 `-Dlive.taifex=true`）
- 單元測試：27 tests（含 `ChasedHighEntryEngine` 3 情境）
- 整合測試 `FullApiIntegrationTests`：25 tests，覆蓋所有主要 API happy path + V4 欄位確認

**外部實機驗證**
- LINE Push API：2026-04-17 驗證成功（`GET /api/system/external/probe?liveLine=true`）
- TAIFEX Open API：`TaifexClientLiveTest` 2026-04-17 驗證通過（加 `-Dlive.taifex=true`）
- Claude API key：待驗證（設 `CLAUDE_ENABLED=true` + `CLAUDE_API_KEY=xxx`，呼叫 `?liveClaude=true`）

**部署 / 設定**
- `application-prod.yml`：Flyway 啟用、`ddl-auto:validate`、所有排程開啟
- `application-local.yml`：Flyway 停用、`ddl-auto:update`、部分排程開啟
- `application-integration.yml`：Flyway 停用、獨立 DB `trading_system_it`、所有排程關閉
- `.env.example`、`scripts/run-local.sh`、`scripts/run-prod.sh`

## 6. 尚未完成（唯一剩餘項目）
- **Claude API key 實機測試**：設 `CLAUDE_ENABLED=true` + `CLAUDE_API_KEY=xxx`，呼叫：
  ```
  GET /api/system/external/probe?liveClaude=true
  ```
  驗收：回傳 `claude.status=OK`，`ai_research_log` 有新增一筆。

## 7. 重要程式入口
- 決策 API：`src/main/java/com/austin/trading/controller/DecisionController.java`
- Dashboard API：`src/main/java/com/austin/trading/controller/DashboardController.java`
- 候選整合：`src/main/java/com/austin/trading/service/CandidateScanService.java`
- 最終決策流程：`src/main/java/com/austin/trading/service/FinalDecisionService.java`
- 排程：`src/main/java/com/austin/trading/scheduler/`
- 系統診斷：`src/main/java/com/austin/trading/controller/SystemController.java`

## 8. 開發規範（務必遵守）
- 每次改動後執行：
```bash
mvn -Dmaven.repo.local=/tmp/m2 test -q
```
- 不要破壞既有 API 路徑與欄位名稱。
- 新增 endpoint 時同步更新：
  - `docs/api-spec.md`
  - `docs/spec.md` 的 0.x 進度區塊

## 9. 交付前檢查清單
- [ ] 編譯與測試通過（62+ tests）
- [ ] migration 與 entity 欄位一致
- [ ] API 文件已更新
- [ ] `docs/spec.md` 進度已更新
- [ ] 本地啟動可驗證主要 API

## 10. 注意事項
- IntelliJ Run 需設 Active profiles = `local`，否則讀 base `application.yml`。
- 如果 DB 無資料，先執行 `./scripts/load-local-seed.sh`。
- 若改動交易規則，務必先更新 `docs/spec.md` 再改程式。
- 正式環境啟動用 `./scripts/run-prod.sh`（會驗證必填 env var）。
