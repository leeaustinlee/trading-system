# Trading System Recovery Diagnosis — 2026-05-19

## Scope / safety

本次為 read-only / shadow-mode 診斷與資料閉環驗證。

- 未改 production BUY path
- 未啟用 auto-ordering
- 未啟用 true-position auto SELL
- 只呼叫既有 live API 與 shadow/backfill/repair endpoint 驗證資料狀態

## Live smoke check

| Endpoint | Result |
|---|---|
| `/actuator/health` | 200, `UP` |
| `/api/scheduler/jobs` | 200, 16 jobs |
| `/api/orchestration/today` | 200, tradingDate=2026-05-19 |
| `/api/orchestration/tasks/today` | 200, 2 tasks |
| `/api/ai/tasks` | 200, 2 tasks |
| `/api/notifications?date=2026-05-19` | 200, 0 rows |
| `/api/portfolio/review` | 200, 4 open position review rows |
| `/api/portfolio/next-day-strategy` | 200 |
| `/api/forward-tracking/summary` | 200, OK |
| `/api/backtest/diagnosis/recent?days=60` | 200 |
| `/api/mainstream/overlap/recent?days=60` | 200 |
| `/api/feature-modes/summary` | 200, 10 features |

Windows portproxy `http://127.0.0.1:8890/actuator/health` also returned `UP` before this report.

## Feedback-loop backfill / repair results

### Forward tracking summary

Before/after:

```json
{
  "status": "OK",
  "source": "CANDIDATE_FORWARD_TRACKING",
  "total": 9,
  "candidateRows": 9,
  "paperTradeRows": 12
}
```

### `POST /api/forward-tracking/backfill-from-paper?days=60`

```json
{
  "written": 0,
  "sourcePaperTrades": 12
}
```

Interpretation: paper_trade 已有對應 forward-tracking seed，沒有新 row 可補。

### `POST /api/forward-tracking/backfill-returns?days=60`

```json
{
  "processedRows": 9,
  "updatedRows": 0,
  "dataGapRows": 9,
  "createdFromPaperRows": 0
}
```

主要 data gap：所有 9 筆皆回報 `DATA_GAP: no future TAIEX trading days after <date>`。
這代表目前無法用 live DB 直接計算 T+5/T+10 forward returns；不能把 T5 null 誤判為 AI score 失敗。

### `POST /api/forward-tracking/repair-theme-trace?days=60`

```json
{
  "repairedRows": 0,
  "skippedRows": 9,
  "dataGaps": [
    "8112: matched candidate_stock has no mainstream theme",
    "8926: matched candidate_stock has no mainstream theme",
    "5388: matched candidate_stock has no mainstream theme"
  ]
}
```

Interpretation: 不是 repair 程式沒跑，而是來源 candidate_stock 本身沒有可映射 mainstream theme。

## Recent paper-trade evidence, 60 days

KPI from `/api/paper-trades/kpi?from=2026-03-20&to=2026-05-19`:

| Metric | Value |
|---|---:|
| Total closed trades | 12 |
| Wins / Losses | 6 / 6 |
| Win rate | 50.0% |
| Avg return | +1.3391% |
| Avg win | +5.4560% |
| Avg loss | -2.7777% |
| Profit factor | 1.9642 |
| Max drawdown | 11.8475 |
| Avg holding days | 1.5833 |

By strategy:

| Strategy | Count | Win rate | Avg return |
|---|---:|---:|---:|
| SETUP | 11 | 54.55% | +1.5734% |
| DAY_TRADE | 1 | 0% | -1.2378% |

By theme:

| Theme | Count | Win rate | Avg return |
|---|---:|---:|---:|
| 半導體/IC | 6 | 50.0% | +2.1631% |
| 記憶體/儲存 | 2 | 50.0% | +1.9848% |
| AI伺服器/電腦週邊 | 1 | 100.0% | +0.9407% |
| UNKNOWN | 2 | 50.0% | -0.2908% |
| 其他強勢股 | 1 | 0% | -1.2378% |

## Case table

| Date | Symbol | Theme | Entry | Stop | Target1 | Exit | Reason | PnL | Root issue |
|---|---|---|---:|---:|---:|---:|---|---:|---|
| 2026-05-12 | 2303 聯電 | 半導體/IC | 104.50 | 89.30 | 102.60 | 104.50 | TP1_HIT | -0.20% | Price plan invalid-looking: target1 below entry; RR negative |
| 2026-05-11 | 5388 中磊 | UNKNOWN | 86.55 | 79.99 | 91.91 | 79.40 | STOP_LOSS | -8.44% | Theme unmapped + low RR + large realized loss; possible stop/price simulation mismatch |
| 2026-05-11 | 8926 台汽電 | UNKNOWN | 50.65 | 46.15 | 53.03 | 54.85 | TP1_HIT | +7.86% | Theme unmapped but trade worked; theme trace quality gap |
| 2026-05-12 | 2303 聯電 | 半導體/IC | 103.25 | 89.30 | 102.60 | 102.75 | TP1_HIT | -0.68% | Target1 below entry; TP1_HIT yet negative PnL |
| 2026-05-07 | 2356 英業達 | AI伺服器/電腦週邊 | 47.72 | 46.25 | 53.14 | 50.45 | TIME_EXIT | +0.94% | MFE +6.87% but exit +0.94%; profit capture too weak |
| 2026-05-08 | 2303 聯電 | 半導體/IC | 97.10 | 90.71 | 104.22 | 91.60 | POSITION_REVIEW_EXIT | -5.90% | Position-review exit caused immediate loss; needs MA/structure confirmation audit |
| 2026-05-07 | 6770 力積電 | 半導體/IC | 63.90 | 54.61 | 62.75 | 65.35 | TP1_HIT | +2.06% | Target1 below entry; RR negative but still profitable |
| 2026-05-07 | 6770 力積電 | 半導體/IC | 56.36 | 54.61 | 62.75 | 63.90 | TP1_HIT | +9.76% | Good RR / good trade |
| 2026-05-06 | 2303 聯電 | 半導體/IC | 80.61 | 78.11 | 89.75 | 98.85 | TP2_HIT | +7.94% | Good trade |
| 2026-05-06 | 2344 華邦電 | 記憶體/儲存 | 95.84 | 92.87 | 106.70 | 113.25 | TP1_HIT | +4.17% | Good trade |
| 2026-05-06 | 2344 華邦電 | 記憶體/儲存 | 95.84 | 92.87 | 106.70 | 108.50 | TP1_HIT | -0.20% | TP1_HIT but negative PnL; exit/slippage/price-plan trace mismatch |
| 2026-04-28 | 8112 至上 | 其他強勢股 | 87.80 | 87.00 | 88.50 | 86.80 | STOP_LOSS | -1.24% | Day trade + unknown regime + very tight stop |

## Diagnosis API root-cause ranking

From `/api/backtest/diagnosis/recent?days=60`:

| Cause | Count / Total | Pct | Interpretation |
|---|---:|---:|---|
| rrShadowValidationStatus | 16 / 21 | 76.19% | RR shadow validation would block many rows; still shadow-only |
| lowRrPct | 7 / 12 | 58.33% | Many trades have poor/negative RR or missing RR |
| themeMisalignmentPct | 3 / 12 | 25.0% | Some trades cannot be tied to mainstream theme due UNKNOWN/OTHER/unmapped theme |
| earlyExitPct | 1 / 12 | 8.33% | At least one case may have been cut before forward path matured |
| stopTooTightPct | 1 / 12 | 8.33% | One tight-stop case |
| regimeMismatchPct | 1 / 12 | 8.33% | One weak/unknown regime entry |
| invalidPricePlanPct | 0 / 12 by API | 0.0% | API does not yet flag target-below-entry cases seen in row evidence |

Important: `aiLayer` still reports `DATA_GAP: missing T5 return rows=9`; therefore AI score failure cannot yet be quantified from T5 outcomes.

## Mainstream overlap evidence

From `/api/mainstream/overlap/recent?days=60`:

| Metric | Value |
|---|---:|
| Total candidates | 140 |
| Candidate mainstream overlap | 47.86% |
| Unmapped / OTHER | 50.0% |
| Today mainstream themes | SEMICONDUCTOR, PCB, MEMORY, AI_SERVER, COOLING |

Theme distribution:

| Theme | Count |
|---|---:|
| OTHER | 70 |
| SEMICONDUCTOR | 22 |
| PCB | 17 |
| MEMORY | 16 |
| AI_SERVER | 9 |
| COOLING | 3 |
| DEFENSE | 2 |
| ROBOTICS | 1 |

Data gaps:

- missing themeTag rows=15, fallback used where possible
- institutional flow themes unavailable; no joined institutional flow source

Interpretation: 系統不是完全脫離主流，約 48% candidate overlap 主流；但 50% OTHER/unmapped 太高，會讓 theme engine / reporting 看起來脫鉤，也會削弱 AI score attribution。

## Most likely current causes of small-loss behavior

1. RR / price-plan quality is the highest-priority issue
   - lowRrPct = 58.33%。
   - 多筆 target1 below entry 或 entryRrRatio negative/low，卻仍被標成 TP1_HIT。
   - 這會造成「看似有停利規則，實際 reward/risk 不合理」。

2. Theme trace / mainstream mapping loss is real
   - Candidate universe overlap only 47.86%。
   - OTHER/unmapped 50%。
   - 5388/8926/8112 repair 失敗原因是 candidate_stock 無 mainstream theme，而非 backfill 沒跑。

3. Exit/holding logic需要 structure confirmation
   - 2303 on 2026-05-08: POSITION_REVIEW_EXIT -5.90%。
   - 5388: STOP_LOSS -8.44%，但 available MFE +3.06%，需查是否在拉回/洗盤/短均破壞前就被 stop 或 price simulation 打出。
   - 2356: MFE +6.87% 但 TIME_EXIT +0.94%，代表停利捕捉不足。

4. AI score 目前不能定罪
   - forward T5/T10 returns are DATA_GAP。
   - 不能把 T5 null 算進 AI score failure denominator。

## P0 recommendations

1. Add stronger shadow price-plan sanity diagnostics
   - flag `target1 <= entry`
   - flag `entryRrRatio <= 0`
   - flag `stop distance too tight / too wide`
   - flag `TP1_HIT but pnlPct <= 0`
   - keep as shadow diagnostic first, no BUY path change

2. Add candidate→trade theme propagation audit
   - 每筆 paper_trade 應可回查 candidate_forward_tracking mainstream theme。
   - 若 paper_trade.themeTag UNKNOWN/NULL，但 candidate has theme，補 trace。
   - 若 candidate itself OTHER，進 manual taxonomy / theme mapping queue。

3. Add structure-confirmed exit shadow comparison
   - Compare current exit with:
     - trailing stop only
     - trailing stop + 5MA confirmation
     - trailing stop + previous low confirmation
     - ATR stop + structure confirmation
     - 5MA/10MA staged exit
   - Persist comparison; do not auto-sell.

4. Fix market data forward-return coverage
   - backfill market_index_daily / stock daily bars for future horizons after trade dates。
   - Until then, keep diagnosis reporting DATA_GAP rather than scoring failure。

## Next implementation prompt

```text
你是 trading-system recovery feedback-loop 實作 Agent。請在 /mnt/d/ai/stock/trading-system 內做 shadow-only P0 補強，不要改 production BUY path，不要啟用 auto-ordering，不要讓任何新規則直接觸發真實 SELL。

目標：補強最近小賠診斷中發現的三個缺口：price-plan sanity、theme propagation audit、structure-confirmed exit shadow comparison。

請實作：
1. PricePlanSanityEngine v2 / 或擴充現有 engine
   - flag target1 <= entry
   - flag target2 <= target1
   - flag entryRrRatio <= 0
   - flag stop distance too tight / too wide
   - flag TP1_HIT but pnlPct <= 0
   - 寫入 paper_trade.sanityResult / sanityViolations 或新增 read-only diagnosis DTO，不改 BUY 決策。

2. ThemePropagationAuditService
   - 以 paper_trade ↔ candidate_forward_tracking ↔ candidate_stock 建立 trace。
   - 報告 trade theme UNKNOWN/NULL 但 candidate 有 mainstream theme 的數量。
   - 報告 candidate 本身 OTHER/unmapped 的數量與樣本。
   - 新增 read-only API `/api/backtest/diagnosis/theme-propagation?days=60`。

3. ShadowStructureExitComparisonService
   - 對 closed paper_trade 比較 current actual exit vs 5MA/10MA/previous-low/ATR+structure confirmation。
   - 資料不足要寫 DATA_GAP，不可合成。
   - 新增 read-only API `/api/backtest/diagnosis/exit-rule-comparison?days=60`。
   - 若已有 shadow_exit_comparison table，優先復用，避免重複 schema。

4. Tests
   - target1 below entry must be flagged。
   - TP1_HIT with negative pnl must be flagged。
   - null T5 return must be DATA_GAP, not AI score failure。
   - theme propagation distinguishes candidate mapping gap vs trade trace loss。
   - exit comparison does not create true-position SELL or production BUY/SELL side effects。

驗證：
- targeted tests
- mvn -q -DskipTests compile
- full `MAVEN_OPTS='-Djdk.attach.allowAttachSelf=true' mvn -q test`
- live restart後驗證新 API。
```
