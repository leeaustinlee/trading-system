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
- `regimeMismatchPct`: `entryRegime` is `C`, contains `WEAK`, `UNKNOWN`, or is missing.
- `aiScoreFailurePct`: `candidate_forward_tracking` rows with high `finalScore` (`scoring.grade_b_min`, default 6.5) but `t5CloseReturnPct <= 0` or missing.

## P1 Forward Truth Backfill

`CandidateForwardReturnBackfillService` is manual-only through `POST /api/forward-tracking/backfill-returns?days=60`; no scheduler is enabled and production BUY/SELL paths are not changed.

The service reads `candidate_forward_tracking` rows in the requested window. If none exist, it creates fallback rows from `paper_trade` and then computes returns from `market_index_daily`. It fills `t1CloseReturnPct`, `t3CloseReturnPct`, `t5CloseReturnPct`, `t10CloseReturnPct`, `mfePct`, `maePct`, `maxDrawdownPct`, `benchmarkReturnPct` using `t00`, and `relativeReturnPct`. The response includes `processedRows`, `updatedRows`, `dataGapRows`, `createdFromPaperRows`, `start`, `end`, and `dataGaps` samples.

The service requires a full T+10 trading-day path from the `t00` calendar and matching stock daily bars. If the daily K path is incomplete or entry price is missing, it reports `DATA_GAP` and does not invent a return.

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
