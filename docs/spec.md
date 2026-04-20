# Trading Decision Platform Spec v1.1 (AI_RULES 整合版)

## 0. 實作進度（截至 2026-04-17）
### 0.1 已完成

**Phase 1 — 骨架**
- Spring Boot 專案、MySQL schema V1~V3、核心 Entity/Repository
- Local seed：`sql/local-seed-phase2.sql` + `scripts/load-local-seed.sh`

**Phase 2 — 決策引擎**
- `MarketGateEngine`、`HourlyGateEngine`、`MonitorDecisionEngine`、`FinalDecisionEngine`
- Scheduler skeleton：`HourlyIntradayGateJob`、`FiveMinuteMonitorJob`、`FinalDecision0930Job`
- 落表：`hourly_gate_decision`、`monitor_decision`、`final_decision`、`scheduler_execution_log`

**Phase 3 — 估值 / 外部 client / 全排程**
- `StockEvaluationEngine`、`PositionSizingEngine`、`StopLossTakeProfitEngine`、`ReviewScoringEngine`、`ChasedHighEntryEngine`
- `PATCH /api/positions/{id}/close`：自動計算 realizedPnl（V4 migration）
- LINE 通知基礎設施：`LineSender`（LINE Push API）、`LineMessageBuilder`、`LineTemplateService`
- 外部 client：`TwseMisClient`、`TwseInstitutionalClient`、`TaifexClient`（多欄位 fallback）、`TpexClient`、`MarketBreadthClient`
- 全排程補完（共 13 個 Job，全部可開關）：
  - `PremarketDataPrepJob`（08:10）、`PremarketNotifyJob`（08:30）
  - `OpenDataPrepJob`（09:01）、`FinalDecision0930Job`（09:30）
  - `HourlyIntradayGateJob`（10:05-13:05）、`FiveMinuteMonitorJob`（每5分鐘）
  - `MiddayReviewJob`（11:00）、`AftermarketReview1400Job`（14:00）
  - `PostmarketDataPrepJob`（15:05）、`PostmarketAnalysis1530Job`（15:30）
  - `T86DataPrepJob`（18:10）、`TomorrowPlan1800Job`（18:30）
  - `ExternalProbeHealthJob`（08:25/15:25）
- `AftermarketReview1400Job`：chasedHighEntry 自動推算（進場均價 vs MIS 日高，0.5% 門檻）
- V4 migration：`close_price`、`realized_pnl`（已確認存在 DB）

**Phase 4 — UI**
- 七頁分頁 SPA（`src/main/resources/static/index.html`）：
  - 總覽 / 候選股 / 持倉 / 損益 / 決策歷史 / AI 研究 / 系統
  - 60 秒自動刷新當前頁

**Phase 5 — AI Adapter（已重構為檔案模式）**
- 直接 Claude API（`AiClaudeClient`、`AiFacade`、5 個 PromptBuilder）已移除
- 保留 `ClaudeCodeRequestWriterService`（寫出 JSON 研究請求）+ `AiResearchService`（讀取/匯入研究結果）
- `AiClaudeConfig`：僅保留 `researchOutputPath` + `requestOutputPath`
- Migration V5：`ai_research_log`；V6：`external_probe_log`
- `SystemController`：`/api/system/external/probe`（dry-run/live）、probe history、migration health
- `MigrationHealthService`：檢查核心表與 V4 欄位（無 Flyway 依賴）

**Phase 6 — BC Sniper v2.0 評分管線**
- `ConsensusScoringEngine`、`WeightedScoringEngine`、`VetoEngine`（14 條規則）
- `FinalDecisionService.applyScoringPipeline()`：JavaStructure → Consensus → Veto → Weighted → FinalRank
- `PUT /api/candidates/{symbol}/ai-scores`：AI 評分回填時同步重算 consensus + final_rank
- PremarketWorkflowService Phase 2（題材 context + Java 結構評分）
- PostmarketWorkflowService Phase 2（每日損益彙總 + 題材評分）

**Phase 7 — Position Layer + Watchlist Layer（v1.1 完成）**
- `PositionDecisionEngine`：持股每日/盤中決策（STRONG/HOLD/WEAKEN/EXIT/TRAIL_UP）
  - 時間衰退（stale_days_without_momentum）、isExtended 分級（NONE/MILD/EXTREME）
  - failedBreakout 快速 EXIT、trailing stop 三階段
  - **Drawdown 機制**：從持倉期間高點回撤 ≥ 3% WEAKEN / ≥ 5% EXIT
- `WatchlistEngine`：觀察名單狀態轉換（ADD/KEEP/PROMOTE_READY/DROP/EXPIRE）
  - decay 機制（觀察天數衰退，momentumStrong 豁免）、READY 門檻更嚴
  - **MarketGrade 過濾**：C 級擋 ADD/PROMOTE；B 級提高 READY 門檻
- 新表：`watchlist_stock`（UNIQUE symbol）、`position_review_log`
- `PositionEntity` 新增：updatedAt, lastReviewedAt, trailingStopPrice, reviewStatus
- `CooldownService`：symbol + theme 維度冷卻
- `FiveMinuteMonitorJob` 整合持倉監控
- `WatchlistWorkflowService` + `WatchlistRefreshJob`（盤後刷新）
- `FinalDecisionService`：watchlist READY 優先、滿倉禁止、score gap 保護 STRONG 持股
  - **Entry trigger**：需有明確進場訊號（decision.require_entry_trigger）
  - **Market-level cooldown**：連續虧損 N 筆或當日虧損超限 → 禁止新倉
- `MarketCooldownService`：連續虧損筆數 + 當日累計虧損檢查
- 40+ config keys（position.review.* / position.trailing.* / watchlist.* / trading.cooldown.* / portfolio.* / decision.*）

**API（全部可用）**
- `GET /api/dashboard/current`
- `GET|POST /api/notifications`、`GET /api/notifications/{id}`
- `GET /api/market/current|history`
- `GET /api/monitor/current|history`、`GET /api/monitor/decisions/current|history`、`POST /api/monitor/state`
- `GET /api/candidates/current|history`
- `GET /api/decisions/current|history`、`GET /api/decisions/hourly-gate/current|history`
- `POST /api/decisions/market-gate/evaluate`、`POST /api/decisions/hourly-gate/evaluate`
- `POST /api/decisions/final/evaluate`、`POST /api/decisions/stock/evaluate`
- `POST /api/decisions/position-sizing/evaluate`、`POST /api/decisions/stoploss-takeprofit/evaluate`
- `GET /api/positions/open`、`GET /api/positions/history`、`POST /api/positions`、`PATCH /api/positions/{id}/close`
- `GET /api/pnl/daily|history|summary`、`POST /api/pnl/daily`
- `GET /api/ai/research`、`POST /api/ai/research/write-request|import-file`
- `GET /api/system/external/probe|probe/history|migration/health`

**Phase 8 — Backtest + Trade Review + Strategy Recommendation（v1.0 完成）**
- `TradeReviewEngine`：單筆交易檢討
  - 12 種 ReviewTag（GOOD_ENTRY_GOOD_EXIT / CHASED_TOO_HIGH / FAILED_BREAKOUT_ENTRY / HELD_TOO_LONG ...）
  - A/B/C/D 四級 grade + strengths/weaknesses/improvementSuggestions
  - MarketCondition（BULL/RANGE/BEAR）區分好/壞環境的交易品質
- `BacktestMetricsEngine`：winRate / profitFactor / maxDrawdown / bestTrade / worstTrade
- `StrategyRecommendationEngine`：策略參數建議
  - sample size 保護（< min_sample_size 時 confidence 降為 LOW 或跳過）
  - 追高 / 假突破 / 持有過久 / 利潤回吐 / 連續虧損 / 勝率分析 6 條分析方向
  - Level 2 — 只寫 strategy_recommendation，不直接改 config
- 新表：`backtest_run`（含 config_snapshot_json）、`backtest_trade`（含 entry_trigger_type）、`trade_review`（支援 reviewer_type + review_version）、`strategy_recommendation`
- `PositionService.close()` 自動觸發 trade review（可 config 關閉）
- `WeeklyTradeReviewJob`（每週五 19:00）
- API：`/api/backtest/*`, `/api/trade-reviews/*`, `/api/strategy-recommendations/*`

**測試（128 tests pass，4 skipped）**
- 單元測試：engine 層含 ConsensusScoringEngine 6、VetoEngine 17、ThemeSelectionEngine 6 等
- 整合測試（`ApiIntegrationTests`）：3 tests，E2E 骨架
- 整合測試（`FullApiIntegrationTests`）：26 tests，涵蓋所有主要 API + AI 評分回填 consensus 驗證
- TAIFEX live（`TaifexClientLiveTest`）：4 tests，加 `-Dlive.taifex=true` 跑實機

**部署 / 設定**
- `application-prod.yml`：`ddl-auto:update`、所有排程開啟（無 Flyway）
- `application-local.yml`：`ddl-auto:update`、部分排程開啟（開發用）
- `application-integration.yml`：獨立 DB `trading_system_it`、所有排程關閉
- `.env.example`：所有環境變數說明（Claude 部分僅保留檔案路徑，API key 已移除）
- `scripts/run-local.sh`、`scripts/run-prod.sh`（含必填欄位驗證）

### 0.2 外部實機驗證（已完成）
- LINE Push API：2026-04-17 驗證成功
- TAIFEX Open API：2026-04-17 驗證通過
- Claude：直接 API 已停用，改用 Claude Code Agent 檔案模式（probe 回傳 SKIPPED）

### 0.3 更新規則
- 每次完成一個功能切片（engine/API/scheduler）後，必須同步更新本章節。
- 「已完成」只記錄可執行且已通過 `mvn test` 的項目。
- 「未完成」需保持可追蹤，不可刪除未落地項目。

## 1. 專案名稱
Trading Decision Platform

## 2. 目標
建立本地可執行的台股中短線交易決策平台，整合市場資料擷取、候選股掃描、市場 Gate、每小時 Gate、5 分鐘監控、個股估值與風控、LINE 通知、AI 研究摘要、UI 查詢與檢視。

## 3. 規則優先級（強制）
1. `Austin台股操盤AI設定-Level3.md`（最高）
2. `AI_RULES_INDEX.md`
3. `CODEX.md` / `CLAUDE.md`
4. 本專案 `docs/spec.md`

若衝突，以上層級高者覆蓋低者。

## 4. 必讀資料來源（執行前）
每次固定分析、排程觸發、決策輸出前，至少先讀：
- `market-snapshot.json`
- `capital-summary.md`
- `intraday-state.json`
- `daily-pnl.csv`
- `market-data-protocol.md`
- `dual-ai-workflow.md`
- `trade-decision-engine.md`
- `market-gate-self-optimization-engine.md`
- `five-minute-monitor-decision-engine.md`
- `final-notification-flow.md`

## 5. 核心原則
- 主策略：中短線 3-10 個交易日；當沖僅例外。
- 09:30 才做最終進場決策，不可跳過。
- 盤中不臨時追高。
- 通知以事件為主，不做 5 分鐘噪音廣播。
- AI 只做研究與摘要，交易規則與風控由 Java engine 控制。
- 最終 LINE 僅由 Codex 發送。

## 6. 資金與持倉硬限制
- 單檔資金原則：3-5 萬。
- 同時最多持股：3 檔。
- 現金比例至少：30%-40%。
- 槓桿 ETF 最多佔可操作資金 50%。
- 融資/權證：預設禁止。
- 當沖例外單：單筆 <= 3 萬、一天最多 1 筆，且需同時滿足強勢盤與突破條件。

## 7. 雙 AI 分工（強制）
### 7.1 Codex
- 抓即時行情/題材/籌碼/資金。
- 執行所有交易引擎與最終決策。
- 發送唯一 LINE 最終通知。

### 7.2 Claude
- 僅輸出 `claude-research-latest.md` 研究內容。
- 不發 LINE，不給最終買進張數。
- 研究前需做候選股現價複核與 10 分鐘方向比對。

### 7.3 缺資料處理
- 若 Claude 研究缺失或過期，Codex 可繼續通知，但需標示「未取得有效 Claude 研究」。

## 8. 市場資料與報價規範
### 8.1 共同資料源
- 以 `market-snapshot.json` 為雙 AI 共同快照。
- 盤中報價超過 10 分鐘視為過期，不得直接給進場建議。

### 8.2 TWSE MIS API 規則
- 09:30 最終決策前，必須重抓候選即時報價。
- 代碼：上市 `tse_代號.tw`、上櫃 `otc_代號.tw`。
- 若 `z = '-'`，不得當 0，需以 bid/ask 中間價估算。
- 非 MIS 不得作主要即時報價來源（除 MIS 不可用時，需標示來源與時間）。

### 8.3 T86 日期規範
- `t86.date` 非當日不可當作今日籌碼加分，只能觀察並標示日期。

## 9. 候選股與掃描規範
- 候選股不得由固定觀察池直接產生。
- 需讀取全市場掃描 `market-breadth-scan.json`（TWSE + TPEx）。
- 每次盤後/明日建議至少輸出 2-3 組強勢族群，每組 2-3 檔代表股。
- 盤後固定輸出雙軌 10 檔：超強勢 5 檔 + 中短線候選 5 檔。
- 09:30 最終收斂最多 3 檔候選，再由最終決策最多選 2 檔可執行計畫。

## 10. 交易 Gate 與決策引擎
### 10.1 行情 Gate（第一層）
- 等級僅 `A/B/C`：
- `A` 主升盤：可正常交易。
- `B` 強勢震盪：最多 1 檔且倉位 <= 30%。
- `C` 震盪/出貨：禁止交易，決策必為休息。

### 10.2 最終決策（第二層）
整合：行情 Gate、Decision Lock、Time Decay、個股估值、即時行情。

強制不交易條件（任一成立）：
- `market_grade = C`
- `decision_lock = LOCKED`
- `time_decay_stage = LATE` 且 `market_grade != A`

### 10.3 候選排除條件
- `risk_reward_ratio < 1.8`（B 盤建議 <2.0 也排除）
- 跌破開盤或昨收
- 假突破
- 接近日高但停損不合理
- 非主流族群
- 估值模式為 `VALUE_HIGH` / `VALUE_STORY` 且市場非 A

### 10.4 允許進場型態
僅允許：`PULLBACK`、`BREAKOUT`、`REVERSAL`。

## 11. Decision Lock / Time Decay / Cooldown
### 11.1 Decision Lock
- `NONE | LOCKED | RELEASED`
- LOCK 條件：`market_grade=C`、`decision=REST`、`hourly_gate=OFF_HARD`、或無持倉且無候選。
- LOCKED 時禁止 `ENTER`。

### 11.2 Time Decay
- `EARLY` 09:00-10:00
- `MID` 10:00-10:30
- `LATE` 10:30 後

強制規則：10:30 後無持倉且市場非 A，強制 `REST + OFF_HARD + monitor OFF + LOCKED`。

### 11.3 Cooldown
- 預設 10 分鐘。
- 同事件冷卻內不重複通知。
- 事件升級可突破冷卻。
- `MARKET_DOWNGRADE` 優先通知。

## 12. 每小時 Gate 規範
### 12.1 Gate 狀態
- `ON`：允許 5 分鐘監控。
- `OFF_SOFT`：暫停，可重啟。
- `OFF_HARD`：今日原則停止，除非明顯結構翻轉。

### 12.2 輸出 JSON 契約（必填）
- `market_grade`
- `market_grade_desc`
- `market_phase`
- `decision`
- `hourly_gate`
- `should_run_5m_monitor`
- `should_notify`
- `trigger_event`
- `hourly_reason`
- `next_check_focus`
- `reopen_conditions`
- `decision_lock`
- `cooldown_minutes`
- `last_event_time`
- `last_event_type`
- `time_decay_stage`
- `summary_for_log`
- `line_message`

### 12.3 通知條件
每小時 Gate 僅在以下變化通知：
- 市場等級變化
- Gate 狀態變化
- 決策變化
- 重新符合監控條件
- 持倉監控策略改變

## 13. 5 分鐘監控規範
### 13.1 監控模式
- `OFF | WATCH | ACTIVE`

### 13.2 輸出 JSON 契約（必填）
- `market_grade`
- `market_phase`
- `decision`
- `monitor_mode`
- `should_notify`
- `trigger_event`
- `monitor_reason`
- `next_check_focus`
- `summary_for_log`
- `decision_lock`
- `cooldown_minutes`
- `last_event_time`
- `last_event_type`
- `time_decay_stage`
- `watchlist_symbols`
- `watchlist_actions`

### 13.3 should_notify 規則
- `OFF`：不得通知。
- `WATCH`：僅市場升降級、候選接近進場、候選失效、watchlist 變化、明確洗盤轉強、假突破時通知。
- `ACTIVE`：僅突破、停損、停利、持倉管理、結構變化時通知。
- 無新事件：`should_notify=false`、`trigger_event=NONE`。

### 13.4 監控啟用條件
- 只有持倉或具關鍵價監控意義時啟用。
- 無持倉/無候選/無關鍵價時，不發 5 分鐘通知。

## 14. 通知規範（LINE）
- 最終通知皆由 Codex 發送。
- 通知語言必須繁體中文行動語句。
- 不得直接暴露程式參數名稱（如 `market_grade`、`decision_lock`、`time_decay_stage`、`risk_reward_ratio`）。
- 若 Claude 有參與研究：署名 `來源：Codex + Claude`；否則 `來源：Codex`。

## 15. 固定排程（沿用現行）
- 08:10 資料預抓（Codex）
- 08:20 研究（Claude）
- 08:30 盤前通知（Codex）
- 09:10 資料預抓（Codex）
- 09:20 研究（Claude）
- 09:30 最終策略（Codex）
- 10:05 / 11:05 / 12:05 / 13:05 每小時 Gate（Codex）
- 10:40 資料預抓（Codex）
- 10:50 研究（Claude）
- 11:00 盤中通知（Codex）
- 14:00 今日檢討（Codex-only）
- 15:10 資料預抓（Codex）
- 15:20 研究（Claude）
- 15:30 盤後通知（Codex）
- 17:40 T86 預抓（Codex）
- 17:50 研究（Claude）
- 18:00 明日建議（Codex）

## 16. API 目標
- `/api/dashboard/current`
- `/api/market/current` `/api/market/history`
- `/api/candidates/current` `/api/candidates/history`
- `/api/decisions/current` `/api/decisions/history`
- `/api/decisions/hourly-gate/current` `/api/decisions/hourly-gate/history`
- `/api/monitor/current` `/api/monitor/history`
- `/api/monitor/decisions/current` `/api/monitor/decisions/history`
- `/api/positions/open` `/api/positions/history`
- `/api/positions` (POST)
- `/api/notifications` `/api/notifications/{id}`
- `/api/pnl/daily` `/api/pnl/history` `/api/pnl/summary`

## 17. 資料庫原則
- 使用 MySQL。
- 歷史紀錄與即時狀態先寫 DB；後續有效能需求再引入 Redis。
- 第一版必要表：
- `market_snapshot`
- `sector_snapshot`
- `candidate_stock`
- `stock_evaluation`
- `final_decision`
- `trading_plan`
- `hourly_gate_decision`
- `monitor_decision`
- `position`
- `daily_pnl`
- `notification_log`
- `trading_state`
- `scheduler_execution_log`

## 18. 禁止事項（第一版即生效）
- 不得把 15:30 候選 5 檔當成立即進場建議。
- 不得跳過 09:30 最終確認。
- 不得在震盪/出貨盤硬交易。
- 不得讓 Claude 執行盤中監控或發 LINE。
- 不得在無持倉且無關鍵價時啟動 5 分鐘監控。
- 不得把前一日 T86 當成今日籌碼。
- 不得以過期報價直接下進場建議。

## 19. 第一版不做
- 自動下單
- 券商 API
- 高頻 tick 級策略
- 複雜回測框架
- 多市場多商品擴展

## 20. 第一版完成標準
系統 local 可運作且具備：
- 定時抓資料與共同快照更新
- 市場 A/B/C 判斷與交易 Gate
- 每小時 Gate 與 5 分鐘監控開關判斷
- Decision Lock/Time Decay/Cooldown
- 候選估值與停損停利產生
- 最終交易計畫輸出（最多 2 檔）
- LINE 事件通知
- UI 可查當前狀態與歷史紀錄
- 可追溯 JSON decision log

## 21. 開發順序
### Phase 1
- Spring Boot 骨架
- MySQL schema
- JPA entity/repository
- `MarketSnapshot` / `TradingState` / `NotificationLog`

### Phase 2
- `MarketGateEngine`
- `HourlyGateEngine`
- `MonitorDecisionEngine`
- `FinalDecisionEngine`

### Phase 3
- `StockEvaluationEngine`
- `StopLossTakeProfitEngine`
- `PositionSizingEngine`

### Phase 4
- Scheduler
- LINE Notify
- Dashboard 基本 UI

### Phase 5
- Claude Code Agent 檔案模式（直接 API 已移除）
- 盤前/盤後研究摘要整合

### Phase 6
- BC Sniper v2.0 評分管線（Consensus / Weighted / Veto / FinalRank）
- PremarketWorkflowService / PostmarketWorkflowService Phase 2
## v2.1 Workflow Correctness / AI Orchestration 一致性更新

本文件的 workflow 與 AI task 規則，以 `docs/workflow-correctness-ai-orchestration-spec.md` 為最新權威規格。

本次 P0++ 更新主題：

1. AI task 狀態機必須嚴格單向，禁止狀態倒退。
2. Claude file bridge 必須使用 `.tmp -> .json` 原子寫入協定。
3. POSTMARKET / T86_TOMORROW task 必須由 Java DataPrep 事先建立，通知 job 只讀結果或明確 fallback。
4. FinalDecision 預設必須等待 Claude + Codex 完成；Codex 未完成不得輸出正常 ENTER。

硬性原則：

- 系統從「固定時間硬跑」改為「時間 + 狀態雙重驅動」。
- 所有 fallback 必須有 reason code，並寫入 DB、orchestration、scheduler log 與 LINE 文案。
- Java file bridge 不可作為正式建 task 手段；正式 task 由 DataPrep / Workflow 建立。
- `final_decision.require_codex` 預設值必須為 `true`。

詳見：

- `docs/workflow-correctness-ai-orchestration-spec.md`
- `docs/scheduler-plan.md`
- `docs/api-spec.md`
- `docs/db-schema.md`
