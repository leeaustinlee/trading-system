# Trading Decision Platform Spec v1.1 (AI_RULES 整合版)

## 0. 實作進度（截至 2026-04-17）
### 0.1 已完成
- Phase 1：Spring Boot 專案骨架、MySQL schema（V1~V3）、核心 Entity/Repository（`market_snapshot`、`trading_state`、`notification_log`）
- API（可用）：
- `GET /api/dashboard/current`
- `GET /api/market/current`
- `GET /api/market/history`
- `GET /api/monitor/current`
- `GET /api/monitor/history`
- `GET /api/monitor/decisions/current`
- `GET /api/monitor/decisions/history`
- `GET /api/notifications`
- `GET /api/notifications/{id}`
- `POST /api/notifications`
- `POST /api/monitor/state`
- `GET /api/candidates/current`
- `GET /api/candidates/history`
- `GET /api/decisions/current`
- `GET /api/decisions/history`
- `GET /api/decisions/hourly-gate/current`
- `GET /api/decisions/hourly-gate/history`
- `POST /api/decisions/market-gate/evaluate`
- `POST /api/decisions/hourly-gate/evaluate`
- `POST /api/decisions/final/evaluate`
- `POST /api/decisions/position-sizing/evaluate`
- `POST /api/decisions/stoploss-takeprofit/evaluate`
- `POST /api/decisions/stock/evaluate`  ← Phase 3 新增
- `GET /api/positions/open` （支援 symbol/page/size）
- `GET /api/positions/history` （支援 symbol/dateFrom/dateTo/page/size）
- `POST /api/positions`
- `GET /api/pnl/daily`
- `GET /api/pnl/history` （支援 dateFrom/dateTo/page/limit）
- `GET /api/pnl/summary`
- `POST /api/pnl/daily`
- Phase 2（第一版）：`MarketGateEngine`、`HourlyGateEngine`、`MonitorDecisionEngine`、`FinalDecisionEngine`
- Phase 3（完成）：
- `StockEvaluationEngine`：valuationMode 分類、SL/TP 計算、RR 計算、includeInFinalPlan 判斷
- `StockEvaluationService`：批次評估並寫入 `stock_evaluation` 表
- `PositionSizingEngine` + `StopLossTakeProfitEngine` 串入 `FinalDecisionService`：09:30 決策自動輸出 suggestedPositionSize / positionMultiplier
- `FinalDecisionSelectedStockResponse` 新增 `suggestedPositionSize`、`positionMultiplier` 欄位
- Position 進階查詢（symbol/dateFrom/dateTo/page/size）
- PnL 進階查詢（dateFrom/dateTo/page/limit）
- Position 關閉流程：`PATCH /api/positions/{id}/close`（自動計算 realizedPnl，V4 migration 加 close_price/realized_pnl）
- LINE 通知基礎設施：`LineNotifyConfig`、`LineSender`、`LineMessageBuilder`、`LineTemplateService`
- Scheduler 補完：`PremarketNotifyJob`（08:30）、`AftermarketReview1400Job`（14:00）、`PostmarketAnalysis1530Job`（15:30）
- `ReviewScoringEngine`：盤型/停損/合規/分數/修正建議
- `FinalDecision0930Job`、`HourlyIntradayGateJob` 整合 `LineTemplateService`
- Scheduler Skeleton（可開關）：
- `HourlyIntradayGateJob`
- `FiveMinuteMonitorJob`
- `FinalDecision0930Job`
- Dashboard API 已整合最新：
- market
- tradingState
- finalDecision
- latest hourlyGateDecision
- latest monitorDecision
- latestNotification
- top candidates
- 排程執行落表：`scheduler_execution_log`
- 決策落表：
- `hourly_gate_decision`
- `monitor_decision`
- `final_decision`
- Local 連線：MySQL `localhost:3330`、`trading_system` schema 已建立
- Local seed：`sql/local-seed-phase2.sql` 與 `scripts/load-local-seed.sh`

- 外部 client 實作（Phase 3）：
  - `TwseMisClient`：TWSE MIS 即時報價（上市 `tse_` + 上櫃 `otc_` 前綴）
  - `TwseInstitutionalClient`：T86 三大法人買賣超（每日盤後）
  - `TaifexClient`：TAIFEX Open API 台指期近月（URL 可設定）
  - `TpexClient`：委派至 TwseMisClient 的 OTC wrapper
  - `MarketBreadthClient`：TWSE MI_INDEX 漲跌家數（盤後）
  - `WebClientConfig`：Netty HttpClient，含 10s 連線 / 15s 回應 timeout + UA header
- 新 Scheduler（可開關）：
  - `PremarketDataPrepJob`（08:10）：抓台指期 + 昨日候選昨收，建 PREMARKET snapshot
  - `PostmarketDataPrepJob`（15:05）：抓大盤漲跌家數 + 候選收盤報價，更新 payload
  - `T86DataPrepJob`（18:10）：抓 T86，更新候選 payload（外資/投信/自營淨買）
- `FiveMinuteMonitorJob` 改用 `LineTemplateService.notifyMonitor()`（與 HourlyGate 一致）
- `AftermarketReview1400Job` 串接真實 PnL：hadLoss / exceededDailyLoss 讀取當日關閉持倉
- `AftermarketReview1400Job` 新增 chasedHighEntry 自動推算（依當日持倉進場均價 vs MIS 日高，0.5% 門檻）
- `PositionRepository` 新增 `findClosedBetween` / `sumRealizedPnlBetween` 查詢

- Phase 5（AI Adapter 完成）：
  - `AiClaudeConfig`：Anthropic API 設定（api-key, model, max-tokens, research-output-path）
  - `AiClaudeClient`：Anthropic Messages API 客戶端，返回 `AiResponse(content, model, tokens)`
  - `AiCodexClient`：將研究結果寫入 `claude-research-latest.md`（路徑可設定）
  - Prompt builders：`PremarketPromptBuilder`、`StockEvaluationPromptBuilder`、`FinalDecisionPromptBuilder`、`HourlyGatePromptBuilder`、`MonitorPromptBuilder`
  - `AiResearchService`：執行研究 → 落表 → 可選寫檔
  - `AiFacade`：`doPremarketResearch` / `doStockEvaluation` / `doFinalDecisionResearch` / `doHourlyGateResearch`
  - `AiResearchLogEntity` + Migration V5 `ai_research_log`
- `AiController`：`GET /api/ai/research`, `POST /api/ai/research/premarket`, `POST /api/ai/research/stock/{symbol}`, `POST /api/ai/research/final-decision`
- `SystemController`：`GET /api/system/external/probe`（TAIFEX / LINE / Claude 探針，支援 dry-run/live）
- `SystemController`：`GET /api/system/external/probe/history`（探針歷史查詢）
- `SystemController`：`GET /api/system/migration/health`（V4/V5/V6 關鍵 migration 健康檢查）
- Migration V6：`external_probe_log`（保存每次探針結果與明細）
- `ExternalProbeHealthJob`（08:25/15:25 可開關）：dry-run 探針，異常時發 SYSTEM_ALERT
- `TaifexClient` 欄位映射容錯加強（Close/PrevClose/Change/Volume 多欄位 fallback）
- 剩餘 Scheduler 全部補完：
  - `MiddayReviewJob`（11:00）：市場狀態 + 持倉摘要 LINE 通知
  - `TomorrowPlan1800Job`（18:30）：整合 T86 後的明日候選計畫
  - `OpenDataPrepJob`（09:01）：開盤後 1 分鐘抓候選股開盤價，補 gap_pct 至 payload

### 0.2 未完成
- LINE 實際 token 設定與 end-to-end 測試（LineSender 框架已備，需設 `trading.line.token`）
- `AiClaudeClient` 實際呼叫 end-to-end 測試（需設 `trading.ai.claude.api-key`）

### 0.2.2 已完成（本輪 2026-04-17）
- V4 migration 欄位確認：integration test 驗證 `position.close_price` / `position.realized_pnl` 存在
- Production profile（`application-prod.yml`）：Flyway 啟用、ddl-auto:validate、所有排程開啟
- `MigrationHealthService` 改善：Flyway 停用時回報 flyway.disabled=true，不誤判為失敗
- `.env.example` 補全說明、新增 `scripts/run-prod.sh` 正式環境啟動腳本（含必填欄位驗證）

### 0.2.1 已完成（本輪 2026-04-17）
- Dashboard 與各頁 UI（Phase 4）：七頁分頁 SPA（總覽/候選股/持倉/損益/決策歷史/AI研究/系統）
- 完整整合測試（FullApiIntegrationTests）：25 tests 涵蓋所有主要 API 端點 happy path
  - Market Gate / Hourly Gate / Final Decision / Stock Eval / Position Sizing / SL-TP evaluate
  - Notification CRUD、Position 開倉/關倉/損益計算、PnL 建立與查詢
  - External Probe dry-run、Migration Health、AI Research list
- TAIFEX end-to-end 驗證框架（TaifexClientLiveTest）：4 tests，加 `-Dlive.taifex=true` 跑實機
  - 欄位 fallback 驗證、非交易日 empty 驗證、null date 不報錯

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
- Claude/Codex adapter
- 盤前/盤後研究摘要整合
