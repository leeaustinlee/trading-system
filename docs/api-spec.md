# API Spec (v2 — BC Sniper)

## Dashboard
- GET `/api/dashboard/current`

## Market
- GET `/api/market/current`
- GET `/api/market/history`

## Candidates
- GET `/api/candidates/current`
- GET `/api/candidates/history`
- POST `/api/candidates/batch`
- PUT `/api/candidates/{symbol}/ai-scores`  ← **Claude / Codex 評分回填**
- PUT `/api/candidates/{symbol}/toggle-include`

## Score Config
- GET `/api/score-config`
- GET `/api/score-config/{key}`
- PUT `/api/score-config/{key}`

## Themes
- GET `/api/themes/snapshots?date=`
- GET `/api/themes/mappings`
- GET `/api/themes/mappings?symbol=`
- GET `/api/themes/mappings?theme=`
- POST `/api/themes/mappings`
- PUT `/api/themes/snapshots/{themeTag}/claude-scores`  ← **Claude 題材評分回填**

## Decisions
- GET `/api/decisions/current`
- GET `/api/decisions/history`
- GET `/api/decisions/hourly-gate/current`
- GET `/api/decisions/hourly-gate/history`
- POST `/api/decisions/market-gate/evaluate`
- POST `/api/decisions/hourly-gate/evaluate`
- POST `/api/decisions/final/evaluate`     ← **觸發完整評分管線**
- POST `/api/decisions/position-sizing/evaluate`
- POST `/api/decisions/stoploss-takeprofit/evaluate`
- POST `/api/decisions/stock/evaluate`

## Monitor
- GET `/api/monitor/current`
- GET `/api/monitor/history`
- GET `/api/monitor/decisions/current`
- GET `/api/monitor/decisions/history`
- POST `/api/monitor/state`

## Positions
- GET `/api/positions/open`   `?symbol=&page=&size=`
- GET `/api/positions/history` `?symbol=&dateFrom=&dateTo=&page=&size=`
- POST `/api/positions`
- PATCH `/api/positions/{id}/close`  ← **新增 (Phase 3)**

## Notifications
- GET `/api/notifications`
- GET `/api/notifications/{id}`
- POST `/api/notifications`

## AI Research（檔案模式，直接 Claude API 已移除）
- GET  `/api/ai/research`  `?date=&type=`
- POST `/api/ai/research/write-request`  寫出 Claude 研究請求 JSON
- POST `/api/ai/research/import-file`  匯入 Claude 研究結果

## Watchlist
- GET  `/api/watchlist/current`  TRACKING + READY 觀察股
- GET  `/api/watchlist/ready`    只取 READY 股
- GET  `/api/watchlist/history`  含 DROPPED / EXPIRED / ENTERED
- POST `/api/watchlist/rebuild`  `?date=`  手動觸發 watchlist 刷新
- PATCH `/api/watchlist/{symbol}/status`  手動調整狀態
- POST `/api/watchlist/position-review/trigger`  手動觸發持倉 review

## Backtest
- POST `/api/backtest/run`                 `{startDate, endDate, runName, notes}`
- GET  `/api/backtest/runs`                列出所有回測
- GET  `/api/backtest/runs/{id}`           取單筆回測
- GET  `/api/backtest/runs/{id}/trades`    取回測交易明細

## Trade Review
- POST `/api/trade-reviews/generate`       批次 review 所有未 review 的已關閉 position
- GET  `/api/trade-reviews`                列出所有 review
- GET  `/api/trade-reviews/{positionId}`   取指定 position 的 review

## Strategy Recommendation
- POST `/api/strategy-recommendations/generate` `?sourceRunId=` 從 trade review 聚合產生建議
- GET  `/api/strategy-recommendations`     列出所有建議
- PATCH `/api/strategy-recommendations/{id}/status` `{status: NEW/REVIEWED/ACCEPTED/REJECTED}`

## System
- GET `/api/system/external/probe` `?taifexDate=&liveLine=&liveClaude=`
- GET `/api/system/external/probe/history` `?limit=`
- GET `/api/system/migration/health`

## PnL
- GET `/api/pnl/daily`
- GET `/api/pnl/history` `?dateFrom=&dateTo=&page=&limit=`  ← 新增日期區間查詢
- GET `/api/pnl/summary`
- POST `/api/pnl/daily`

---

## 新增 API 說明

### POST /api/decisions/stock/evaluate
個股估值評估（StockEvaluationEngine）

Request body:
```json
{
  "symbol": "2330",
  "marketGrade": "A",
  "entryType": "BREAKOUT",
  "entryPrice": 100.0,
  "stopLossPercent": 5.0,
  "takeProfit1Percent": 10.0,
  "takeProfit2Percent": 15.0,
  "volatileStock": false,
  "nearDayHigh": false,
  "aboveOpen": true,
  "abovePrevClose": true,
  "mainStream": true,
  "valuationScore": 30
}
```
Response: `StockEvaluateResult`（含 valuationMode, entryPriceZone, stopLossPrice, takeProfit1, takeProfit2, riskRewardRatio, includeInFinalPlan, rejectReason, rationale）

### GET /api/positions/open
```
?symbol=2330     # 可選，精確匹配
&page=0
&size=50
```

### GET /api/positions/history
```
?symbol=2330            # 可選
&dateFrom=2026-04-01    # ISO 日期，以 openedAt 過濾
&dateTo=2026-04-17
&page=0
&size=200
```

### GET /api/pnl/history
```
?dateFrom=2026-04-01   # ISO 日期，以 tradingDate 過濾
&dateTo=2026-04-17
&page=0
&limit=200             # 與 dateFrom/dateTo 同時存在時，limit 當 pageSize 用
```

### FinalDecisionSelectedStockResponse 欄位更新
新增兩個欄位（09:30 流程自動填充，直接 API 呼叫時為 null）：
- `suggestedPositionSize` (Double): 建議倉位金額（元）
- `positionMultiplier` (Double): 倉位乘數（0.0~1.0）

### GET /api/system/external/probe
外部服務探針（TAIFEX / LINE / Claude）。

```
?taifexDate=2026-04-17   # 可選，預設 today
&liveLine=false          # true 時實際送一則 LINE Probe 訊息
&liveClaude=false        # true 時實際呼叫 Claude 一次
```

回傳：`checkedAt`, `taifexDate`, `liveLine`, `liveClaude`，以及 `taifex/line/claude` 三個 probe 結果（`status`, `success`, `detail`）。

### GET /api/system/external/probe/history
外部探針歷史紀錄（來源：`external_probe_log`）。

```
?limit=50   # 1~200
```

### GET /api/system/migration/health
檢查 DB schema 關鍵落地狀態（核心表與欄位存在性，無 Flyway 依賴）。

目前檢查：
- `position.close_price`
- `position.realized_pnl`
- 核心表：`position`、`ai_research_log`、`external_probe_log`
## v2.1 AI Task API 契約變更

最新 workflow correctness 規格見 `docs/workflow-correctness-ai-orchestration-spec.md`。

AI task API 必須遵守嚴格狀態機：

| Endpoint | 允許前置狀態 | 成功後狀態 |
|---|---|---|
| `POST /api/ai/tasks/{id}/claim-claude` | `PENDING` | `CLAUDE_RUNNING` |
| `POST /api/ai/tasks/{id}/claude-result` | `PENDING`, `CLAUDE_RUNNING` | `CLAUDE_DONE` |
| `POST /api/ai/tasks/{id}/claim-codex` | `CLAUDE_DONE` | `CODEX_RUNNING` |
| `POST /api/ai/tasks/{id}/codex-result` | `CLAUDE_DONE`, `CODEX_RUNNING` | `CODEX_DONE` |
| `POST /api/ai/tasks/{id}/finalize` | `CODEX_DONE` | `FINALIZED` |
| `POST /api/ai/tasks/{id}/fail` | `PENDING`, `CLAUDE_RUNNING`, `CODEX_RUNNING` | `FAILED` |
| `POST /api/ai/tasks/{id}/expire` | non-terminal states | `EXPIRED` |

非法狀態轉移必須回：

```json
{
  "success": false,
  "errorCode": "AI_TASK_INVALID_STATE",
  "message": "Cannot submit Claude result when task status is CODEX_DONE",
  "taskId": 42,
  "currentStatus": "CODEX_DONE",
  "expectedStatuses": ["PENDING", "CLAUDE_RUNNING"]
}
```

Codex 未完成且 `final_decision.require_codex=true` 時，FinalDecision API 不得產出正常 `ENTER`。
