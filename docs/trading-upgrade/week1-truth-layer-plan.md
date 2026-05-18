# Week 1 Truth Layer Optimization Plan

目的：先補「可驗證真相層」，不新增 BUY/SELL 策略，不改 production decision semantics，不讓 shadow rule 變 live。

## 工作模式

- Hermes：任務拆分、分派、驗收、測試、最終報告。
- Coding Agent：負責最小必要實作與測試，不 commit。
- Review Agent：唯讀審查 diff/spec/safety，不修改檔案。

## Week 1 任務拆分

### W1-1 Decision Snapshot Ledger（第一步，現在執行）

目標：每次 FinalDecisionService 產生 final decision 時，持久化一份 side-effect-only snapshot，用於 replay/backtest/AI correlation/稽核。

必要內容：
- finalDecisionId
- tradingDate
- sourceTaskType / preferTaskType
- aiTaskId / aiStatus / aiReadinessMode / fallbackReason（若可取得）
- finalDecisionCode
- selected / rejected / watch / merged symbols 摘要
- candidate universe snapshot
- candidate scores：javaStructureScore、claudeScore、codexScore、aiWeightedScore、consensusScore、finalRankScore、disagreementPenalty、veto
- market context：marketGrade、decisionLock、timeDecay、marketRegime（若可取得）
- gate trace：veto / priceGate / portfolio/risk/timing/setup 目前能取得多少先存多少，不可為了 snapshot 大改主流程
- decisionTrace payload copy（若現有 final_decision 已有）
- createdAt

安全限制：
- 不改 FinalDecisionEngine 的 ENTER / WAIT / REST 邏輯。
- 不改 VetoEngine / PriceGate / PortfolioRisk 的判斷。
- Snapshot 寫入失敗不得讓正式決策失敗；只能 log warn。
- 不自動下單、不自動賣出。
- 不把 snapshot API 當交易訊號。

建議實作：
- SQL migration：decision_snapshot_ledger table。
- Entity / Repository。
- Service：DecisionSnapshotLedgerService。
- DTO / Controller：read-only API，例如：
  - GET /api/decision-snapshots/recent?limit=20
  - GET /api/decision-snapshots/final-decision/{finalDecisionId}
  - GET /api/decision-snapshots/{id}
- 在 FinalDecisionService persist final_decision 後呼叫 ledger service。
- 若既有 decisionTrace map 已完整，可先存 JSON payload，後續 Week 2 再正規化欄位。

驗收標準：
- compile pass。
- targeted tests pass。
- 單元測試需涵蓋：
  1. snapshot service 可建立 ledger。
  2. controller recent endpoint 可回傳。
  3. ledger write failure 不影響 FinalDecisionService output（可用 service-level fallback 或至少測 snapshot service isolation）。
- git diff 不包含 production BUY/SELL logic semantic changes。

### W1-2 Scheduler Health Level

目標：排程狀態從 boolean SUCCESS/FAILED 升級成資料品質 aware。

必要狀態：
- SUCCESS_REAL
- SUCCESS_WITH_FALLBACK
- DEGRADED
- EMPTY_DATA
- SKIPPED
- FAILED

優先納入：Postmarket breadth=0/0、FiveMinute heartbeat suppressed、External probe skipped、AI timeout/expired。

### W1-3 Notification Delivery Truth

目標：notification_log 不再被誤認為 delivered。

必要欄位/表：
- attempted
- delivered
- provider
- providerHttpStatus
- providerMessageId
- errorCode / errorBody
- retryCount

### W1-4 Feature Mode API

目標：清楚標示每個 engine 現在是 LIVE / SHADOW / OBSERVATION / TRACE_ONLY / OFF。

首批 feature：
- ThemeLiveDecision
- ThemeShadowMode
- MomentumDecision
- MomentumObservation
- ChasedHighGate
- ShadowExitRuleEngine
- PaperTrade
- ReplayBacktest
- AI Score Merge
- Codex Overlay

## Week 2 預告

- Theme Taxonomy
- Theme Mapping
- Candidate Universe Versioning
- Forward Tracking Upgrade
- Replay Backtest Skeleton

## Week 3-4 預告

- Position Health Engine shadow mode
- Exit Classification
- Leader Detection
- Mainstream Overlap Report
- AI Correlation Analytics

## Month 2 預告

- Replay-based validation
- Shadow A/B framework
- Strategy split by regime
- Adaptive weighting recommendation + human approval
