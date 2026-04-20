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

**Phase 5 — AI Adapter（已重構為檔案模式）**
- 直接 Claude API（`AiClaudeClient`、`AiFacade`、5 個 PromptBuilder）已移除
- 保留 `ClaudeCodeRequestWriterService`（寫出 JSON 研究請求）+ `AiResearchService`（讀取/匯入研究結果）
- `AiClaudeConfig`：僅保留 `researchOutputPath` + `requestOutputPath`
- Migration V5：`ai_research_log`；V6：`external_probe_log`
- `SystemController`：`/api/system/external/probe`（dry-run/live）、probe history、migration health
- `MigrationHealthService`：檢查核心表與 V4 欄位存在性（無 Flyway 依賴）

**Phase 6 — BC Sniper v2.0 評分管線**
- `ConsensusScoringEngine`、`WeightedScoringEngine`、`VetoEngine`（14 條規則）
- `FinalDecisionService.applyScoringPipeline()`：JavaStructure → Consensus → Veto → Weighted → FinalRank
- `CandidateResponse` 22 欄位（含 aiWeightedScore、consensusScore、disagreementPenalty）
- UI 等級徽章 A+/A/B/C + 新分數欄位
- PremarketWorkflowService Phase 2（題材 context + Java 結構評分）
- PostmarketWorkflowService Phase 2（每日損益彙總 + 題材評分）

**測試**
- 93 tests pass，4 skipped（TAIFEX live，需 `-Dlive.taifex=true`）
- 單元測試：含 ConsensusScoringEngine 6 情境、VetoEngine 17 情境、ThemeSelectionEngine 6 情境
- 整合測試 `FullApiIntegrationTests`：26 tests，涵蓋所有主要 API happy path + AI 評分回填 consensus 驗證

**外部實機驗證**
- LINE Push API：2026-04-17 驗證成功（`GET /api/system/external/probe?liveLine=true`）
- TAIFEX Open API：`TaifexClientLiveTest` 2026-04-17 驗證通過（加 `-Dlive.taifex=true`）
- Claude：直接 API 已停用，改用 Claude Code Agent 檔案模式（probe 回傳 SKIPPED）

**部署 / 設定**
- `application-prod.yml`：`ddl-auto:update`、所有排程開啟（無 Flyway）
- `application-local.yml`：`ddl-auto:update`、部分排程開啟
- `application-integration.yml`：獨立 DB `trading_system_it`、所有排程關閉
- `.env.example`、`scripts/run-local.sh`、`scripts/run-prod.sh`

## 6. 已完成（無剩餘任務）
所有 Phase 1~6 功能已落地，測試通過，無阻塞性待辦。

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
- [ ] 編譯與測試通過（93+ tests）
- [ ] entity 欄位與 DB schema 一致（ddl-auto:update 自動同步）
- [ ] API 文件已更新
- [ ] `docs/spec.md` 進度已更新
- [ ] 本地啟動可驗證主要 API

## 10. 注意事項
- IntelliJ Run 需設 Active profiles = `local`，否則讀 base `application.yml`。
- 如果 DB 無資料，先執行 `./scripts/load-local-seed.sh`。
- 若改動交易規則，務必先更新 `docs/spec.md` 再改程式。
- 正式環境啟動用 `./scripts/run-prod.sh`（會驗證必填 env var）。
## v2.1 Claude File Bridge 合約

Claude 不直接呼叫 Java localhost，也不直接發 LINE。Claude 只把研究結果寫入 file bridge，由 Java watcher 匯入 DB。

正式寫檔協定：

```text
1. 先寫入 claude-submit/claude-<TASK_TYPE>-<YYYY-MM-DD>-<HHmm>-task-<TASK_ID>.tmp
2. 寫完後 rename 成 claude-submit/claude-<TASK_TYPE>-<YYYY-MM-DD>-<HHmm>-task-<TASK_ID>.json
3. Java watcher 只讀 .json，不讀 .tmp
```

JSON 必填：

```json
{
  "taskId": 42,
  "taskType": "POSTMARKET",
  "tradingDate": "2026-04-20",
  "contentMarkdown": "Claude research markdown",
  "scores": {"3231": 8.5},
  "thesis": {"3231": "研究摘要"},
  "riskFlags": ["高檔爆量"],
  "generatedAt": "2026-04-20T15:20:00+08:00",
  "schemaVersion": "claude-submit-v2.1"
}
```

Claude 必須假設 task 已由 Java DataPrep 建立。若沒有 taskId，不得自行創造 id；缺 taskId 時 Java bridge 只能 fallback 尋找同日同 taskType 的最新 task。

禁止事項：

- 不得寫半成品 `.json`。
- 不得直接寫 `processed/`、`failed/`、`retry/`。
- 不得直接發 LINE。
- 不得把研究結果包裝成最終下單決策；最終決策由 Codex / Java 完成。

完整規格見 `docs/workflow-correctness-ai-orchestration-spec.md`。
