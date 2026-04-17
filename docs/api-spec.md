# API Spec (v1)

## Dashboard
- GET `/api/dashboard/current`

## Market
- GET `/api/market/current`
- GET `/api/market/history`

## Candidates
- GET `/api/candidates/current`
- GET `/api/candidates/history`

## Decisions
- GET `/api/decisions/current`
- GET `/api/decisions/history`
- GET `/api/decisions/hourly-gate/current`
- GET `/api/decisions/hourly-gate/history`
- POST `/api/decisions/market-gate/evaluate`
- POST `/api/decisions/hourly-gate/evaluate`
- POST `/api/decisions/final/evaluate`
- POST `/api/decisions/position-sizing/evaluate`
- POST `/api/decisions/stoploss-takeprofit/evaluate`
- POST `/api/decisions/stock/evaluate`  ← **新增 (Phase 3)**

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

## AI Research
- GET  `/api/ai/research`  `?date=&type=`
- POST `/api/ai/research/premarket`  `?txfSummary=&globalSummary=`
- POST `/api/ai/research/stock/{symbol}`  `?date=`
- POST `/api/ai/research/final-decision`  `?date=`

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
檢查 migration 關鍵落地狀態（表/欄位/Flyway 版本）。

目前檢查：
- `position.close_price`
- `position.realized_pnl`
- `ai_research_log`
- `external_probe_log`
- Flyway `V4 / V5 / V6`
