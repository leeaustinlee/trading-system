# Trading Recovery Feedback Loop

## Executive Summary

This upgrade turns the current AI stock picker into a shadow-first decision analytics loop. Production BUY behavior is unchanged: price-plan sanity, position health, and alternative exits are recorded as diagnosis fields/logs only. Real-position auto close remains controlled by the existing `position.review.auto_close.paper_only` safety flag.

## Architecture Mermaid

```mermaid
flowchart TD
  Candidate[Candidate / Final Decision] --> Paper[Paper Trade]
  Paper --> Sanity[PricePlanSanityEngine\nshadow flags]
  Paper --> MTM[Paper MTM]
  Position[Open Position Review] --> Health[PositionHealthEngine\nshadow diagnosis]
  Position --> ExitCompare[ShadowExitRuleEngine\nMA/prev-low/ATR/hybrid]
  MTM --> ExitCompare
  Health --> HealthLog[(position_health_log)]
  ExitCompare --> ShadowLog[(shadow_exit_comparison)]
  Paper --> BacktestDiag[/api/backtest/diagnosis/recent]
  Candidate --> Forward[/api/forward-tracking/*]
  Candidate --> Mainstream[/api/mainstream/overlap/recent]
```

## Data Findings Via API

- Price plan sanity: `GET /api/paper/open` and `GET /api/paper/closed` expose `sanityResult`, `sanityViolations`, intended/simulated entry/exit prices, entry grade/RR/regime, and forward returns.
- Forward tracking health: `GET /api/forward-tracking/summary` now reports both `candidateRows` and `paperTradeRows`; if candidate tracking is empty while paper trades exist, it returns `DATA_GAP`.
- Forward return backfill: `POST /api/forward-tracking/backfill-returns?days=60` uses only `market_index_daily` to fill candidate 1D/3D/5D/10D returns, MFE, MAE, max drawdown, benchmark return, and relative return. If `candidate_forward_tracking` is empty, it first seeds rows from `paper_trade` using the existing idempotent key `(tradingDate, stockId, finalDecision)`.
- Backtest diagnosis: `GET /api/backtest/diagnosis/recent?days=30` returns trade, strategy, exit, regime, theme, and AI score-vs-return layers.
- Mainstream alignment: `GET /api/mainstream/overlap/recent?days=30` returns candidate overlap %, top themes, breakout/continuation counts, and low-overlap reason hints.

## Backtest Comparison

The new diagnosis endpoint compares closed paper trades by:

- Trade layer: total trades, win rate, average PnL, average holding days, max drawdown, profit factor, expectancy.
- Strategy layer: setup/momentum/theme tags from paper trade entry snapshots.
- Exit layer: current fixed exit reason groups plus shadow exit logs in `shadow_exit_comparison`.
- AI layer: `candidate_forward_tracking.final_score` vs T5 return correlation when at least three usable score/return pairs exist; otherwise it reports `DATA_GAP`.

## Root Cause Ranking

`GET /api/backtest/diagnosis/recent?days=30` now includes `rootCauseRanking`. Each item returns `count`, `total`, `pct`, up to five `evidenceSample` rows (`symbol`, `date`, `reason`), and an `interpretation`. Empty denominators are reported as `DATA_GAP`.

- `invalidPricePlanPct`: paper trades where `sanityResult != PASS`.
- `lowRrPct`: paper trades where `entryRrRatio` is below `price_plan.min_rr.setup` or null. The denominator is all `paper_trade` rows in the window; null RR is explicitly treated as `DATA_GAP/low RR`.
- `earlyExitPct`: `STOP_LOSS` or `TRAILING_STOP` exits where `mfePct > 0` or `return5d > pnlPct`.
- `stopTooTightPct`: entry-to-stop distance below `price_plan.stop_min_loss_pct` or `<= 2%`.
- `themeMisalignmentPct`: `themeTag` normalizes to `UNKNOWN` or `OTHER`, including `其他強勢股` and unmapped text.
- `themeLostInTradePct`: paper trade theme is `UNKNOWN`, null, or `OTHER`, while same-day `candidate_stock` or repaired `candidate_forward_tracking` has a normalized mainstream theme. Interpretation: 候選層題材存在，但交易層未繼承題材資訊，導致診斷與選股主流性失真。
- `regimeMismatchPct`: `entryRegime` is `C`, contains `WEAK`, `UNKNOWN`, or is missing.
- `aiScoreFailurePct`: `candidate_forward_tracking` rows with high `finalScore` (`scoring.grade_b_min`, default 6.5) but `t5CloseReturnPct <= 0` or missing.

## P1 Forward Truth Backfill

`CandidateForwardReturnBackfillService` is manual-only through `POST /api/forward-tracking/backfill-returns?days=60`; no scheduler is enabled and production BUY/SELL paths are not changed.

The service reads `candidate_forward_tracking` rows in the requested window. If none exist, it creates fallback rows from `paper_trade` and then computes returns from `market_index_daily`. It fills `t1CloseReturnPct`, `t3CloseReturnPct`, `t5CloseReturnPct`, `t10CloseReturnPct`, `mfePct`, `maePct`, `maxDrawdownPct`, `benchmarkReturnPct` using `t00`, and `relativeReturnPct`. The response includes `processedRows`, `updatedRows`, `dataGapRows`, `createdFromPaperRows`, `benchmarkHorizon`, `start`, `end`, and `dataGaps` samples.

P1.5 changes the return fill from all-or-nothing to partial horizon. T1/T3/T5 are written when those horizons have completed even if T10 is still missing. `benchmarkReturnPct`, `relativeReturnPct`, MFE, MAE, and max drawdown use the largest completed horizon, preferring T10 then T5/T3/T1. Missing horizons remain explicit `DATA_GAP` samples such as `T10 missing stock daily bar`.

## P1.5 Manual Market Daily Bars Backfill

`POST /api/market-index/backfill-symbols?days=90&symbols=2330,2303` is a manual-only API. It fetches `t00` with `TwseHistoryClient.fetchTaiexMonth` and each requested symbol with `fetchStockMonth`, then idempotently upserts rows into `market_index_daily`.

If `symbols` is blank, the service resolves up to 50 distinct symbols from recent `paper_trade.symbol`, `candidate_forward_tracking.stockId`, and `candidate_stock.symbol`. This prevents a broad candidate universe from flooding TWSE. The response returns `requestedSymbols`, `resolvedSymbols`, `upsertedRows`, `skippedSymbols`, `dataGaps`, `from`, and `to`.

TWSE `STOCK_DAY` may not return OTC symbols. Those rows are reported as `DATA_GAP`; the service does not synthesize prices and does not fail the whole request when one symbol has no data.

## P1.5 Theme Trace Repair

`POST /api/forward-tracking/repair-theme-trace?days=60` repairs missing trace fields by matching same-day `candidate_stock` rows on symbol. It writes nullable `candidate_forward_tracking.themeTag`, `themeReason`, and `sourceCandidateId` when candidate evidence has a normalized mainstream theme.

For `paper_trade`, repair is limited to `is_shadow=true` rows with missing or unknown theme and a clear same-day same-symbol `candidate_stock` match. This keeps the production BUY path unchanged and does not introduce any SELL behavior. The response returns `repairedRows`, `skippedRows`, `dataGaps`, `from`, and `to`.

## P1.5 DATA_GAP Semantics

- Daily bar gaps mean TWSE did not provide usable bars or the local `market_index_daily` path is incomplete.
- Horizon gaps are per horizon; T10 missing does not invalidate T1/T3/T5.
- Theme trace gaps mean no same-day candidate match, or the matched candidate still normalizes to `UNKNOWN`/`OTHER`.
- All P1.5 APIs are manual endpoints. No scheduler, auto-order, production BUY change, or real-position SELL trigger is added.

## P1 Mainstream Normalization

`MainstreamOverlapReportService` normalizes `themeTag` plus reason keywords into `PCB`, `MEMORY`, `AI_SERVER`, `ROBOTICS`, `DEFENSE`, `POWER`, `COOLING`, `SEMICONDUCTOR`, `OTHER`, and `UNKNOWN`.

`GET /api/mainstream/overlap/recent?days=30` now returns `topThemeByCandidateCount`, `topThemeByBreakoutCount`, `topThemeByContinuationCount`, `candidateOverlapPct`, `unmappedPct`, `dataGaps`, and `institutionalFlowThemes`. Institutional flow is reported as `DATA_GAP` until a real institutional flow source is joined; it is not downgraded to fake `UNKNOWN` analysis.

## Implementation Spec

- `PricePlanSanityEngine` is a pure evaluator. Defaults are seeded in `sql/V33__trading_recovery_feedback_loop.sql` and mirrored in `application.yml`. `shadow_only=true` records flags but does not block BUY.
- `FixedRuleExitEvaluator` now ignores invalid TP targets while preserving stop-first priority.
- `PositionHealthEngine` is pure and emits score, structure/volume/relative-strength/chip status, exit tier, reasons, and data gaps.
- `ShadowExitRuleEngine` compares trailing stop, MA5, MA10, previous low, ATR, and hybrid stop. Missing inputs produce `DATA_GAP`.
- `position_health_log` and `shadow_exit_comparison` persist shadow diagnosis for review and paper MTM paths.
- `CandidateForwardTrackingService.backfillFromPaperTrades(days)` provides an explicit local flow to seed candidate tracking from existing paper trades.

## Next-Step Claude/Codex Prompts

- "Use `/api/backtest/diagnosis/recent?days=30` and `/api/mainstream/overlap/recent?days=30` to rank the top three current strategy data gaps. Do not propose production BUY changes."
- "Compare `shadow_exit_comparison` outcomes for MA5, MA10, previous low, ATR, and hybrid exits against current paper exits over the last 30 days. Return only shadow recommendations."
- "Inspect candidates whose `sanityResult=SHADOW_REJECT`; group by violation and estimate how much paper PnL would change if they were excluded, keeping production unchanged."
