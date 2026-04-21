# Production Swing Redesign Progress Board

Last updated: 2026-04-21 (P2 全部 DONE)

## 1. Working Mode

Use this board as the source of truth for implementation progress.

Rules:
- Claude updates status while implementing
- Codex reviews completed chunks before the next chunk starts
- Do not mark a section complete without code, tests, and a short review note

Status values:
- `TODO`
- `IN_PROGRESS`
- `BLOCKED`
- `DONE`
- `REVIEWED`

## 2. P0 Workstream

### 2.1 Regime Layer

- Status: `DONE`
- Deliverables:
  - `MarketRegimeEngine`
  - `MarketRegimeService`
  - `MarketRegimeDecisionEntity`
  - repository + migration
  - integration into hourly/monitor/final workflows
- Done when:
  - regime is persisted
  - monitor/hourly consume regime output
  - tests cover 4 regime classifications
- Codex review check:
  - no duplicate market meaning logic left in old engines

### 2.2 Ranking Layer

- Status: `DONE`
- Deliverables:
  - `StockRankingEngine`
  - `StockRankingService`
  - `StockRankingSnapshotEntity`
  - migration + repository
- Done when:
  - `FinalDecisionService` no longer computes ranking internally
  - top-N candidates are persisted with score breakdown
- Codex review check:
  - candidate loading and ranking responsibilities are separated cleanly

### 2.3 Setup Layer

- Status: `DONE`
- Deliverables:
  - `SetupEngine`
  - `SetupValidationService`
  - `SetupDecisionEntity`
  - migration + repository
- Done when:
  - exactly 3 setup types are supported
  - setup output includes ideal entry, invalidation, stop, tp1, tp2
- Codex review check:
  - setup logic is not buried in `StockEvaluationEngine`

### 2.4 Timing Layer

- Status: `DONE`
- Deliverables:
  - `ExecutionTimingEngine`
  - `ExecutionTimingService`
  - `ExecutionTimingDecisionEntity`
  - migration + repository
- Done when:
  - breakout/pullback/event timing rules are implemented
  - stale signal and delay tolerance are persisted
  - timing is required before any enter decision
- Codex review check:
  - score alone cannot bypass timing

### 2.5 Risk Layer

- Status: `DONE`
- Deliverables:
  - `PortfolioRiskEngine`
  - `PortfolioRiskService`
  - `PortfolioRiskDecisionEntity`
  - `ThemeExposureService`
  - migrations + repositories
- Done when:
  - portfolio hard blocks are separated from execution
  - size is computed only after hard checks pass
- Codex review check:
  - no risk gating left inside `FinalDecisionService`

### 2.6 Execution Layer

- Status: `DONE`
- Deliverables:
  - `ExecutionDecisionService`
  - `ExecutionDecisionEngine`
  - `ExecutionDecisionLogEntity`
  - adapter from legacy final decision API
- Done when:
  - Java is the only place that emits `ENTER / SKIP / EXIT / WEAKEN`
  - execution trace references upstream decision ids
- Codex review check:
  - Codex can veto only, not force entry

## 3. P1 Workstream

### 3.1 Theme Strength Upgrade

- Status: `DONE`
- Deliverables:
  - `ThemeStrengthEngine`
  - `ThemeStrengthService`
  - `ThemeStrengthDecisionEntity`
- Done when:
  - theme stage and decay are persisted
  - ranking/setup consume theme tradability

### 3.2 Trade Attribution Redesign

- Status: `DONE`
- Deliverables:
  - `TradeAttributionEngine`
  - `TradeAttributionService`
  - `TradeAttributionEntity`
  - review service refactor
- Done when:
  - setup/regime/delay/MFE/MAE are stored for closed trades
  - weekly learning uses attribution data instead of fallbacks

### 3.3 Workflow Rewire

- Status: `DONE`
- Deliverables:
  - `PostmarketWorkflowService` — Step 1a (daily TradeReview+Attribution) + Step 2b (ThemeStrength)
  - `IntradayDecisionWorkflowService` — pipeline trace log banner
  - `WeeklyTradeReviewJob` — attribution count in weekly summary
  - `PostmarketWorkflowPipelineTests` — 6 unit tests
- Done when:
  - `Regime -> Theme -> Ranking -> Setup -> Timing -> Risk -> Execution -> Review` is traceable in code and logs
- Codex review note:
  - IntradayDecision logs pipeline chain before calling FinalDecisionService
  - Postmarket now triggers ThemeStrengthService.evaluateAll (persists theme_strength_decision) and TradeReviewService.generateForAllUnreviewed (persists review + attribution for all new closed positions) — daily, not just weekly
  - Weekly job logs total attribution record count

## 4. P2 Workstream

### 4.1 Bounded Learning

- Status: `DONE`
- Deliverables:
  - `StrategyRecommendationEngine`: `AttributionStats` record + `analyze(stats, attrStats)` overload + `applyBoundedGuard()` + `analyzeTimingQuality()` + `analyzeSetupTypePerformance()`
  - `StrategyRecommendationService`: injects `TradeAttributionService`, builds `AttributionStats`, calls new engine overload
  - `sql/V19__bounded_learning_config.sql`: `learning.allowed.keys` whitelist + attribution thresholds
  - `StrategyRecommendationEngineTests`: 9 new tests (bounded guard + attribution analysis)
- Done when:
  - weekly review can recommend bounded timing/risk parameter changes
- Codex review note:
  - Bounded guard is permissive when `learning.allowed.keys` absent (backward compat)
  - PARAM_ADJUST/TAG_FREQUENCY/RISK_CONTROL only emit for keys in allowed list
  - INFO/OBSERVATION always pass through (no parameter change)
  - Attribution timing analysis feeds from TradeAttributionService.findAll()

### 4.2 Benchmark Analytics

- Status: `DONE`
- Deliverables:
  - `BenchmarkAnalyticsEngine` — alpha computation, verdict (OUTPERFORM/MATCH/UNDERPERFORM), payload JSON
  - `BenchmarkAnalyticsService` — loads attribution + theme snapshot, builds input, persists results, idempotent
  - `BenchmarkAnalyticsEntity` + `BenchmarkAnalyticsRepository`
  - `sql/V20__benchmark_analytics.sql`
  - `WeeklyTradeReviewJob` — weekly benchmark call + verdict in summary log
  - `BenchmarkAnalyticsEngineTests` — 15 tests
- Codex review note:
  - Market benchmark = mean avg_gain_pct across all theme snapshots in period
  - Traded-theme benchmark falls back to market average in v1 (themeTag not in attribution output)
  - MATCH band = ±0.5%; can be config-driven in future

### 4.3 Exit-Regime Integration

- Status: `DONE`
- Deliverables:
  - `ExitRegimeIntegrationEngine` — override rules: PANIC→EXIT, WEAK+DECAY→EXIT, DECAY+nonBull→WEAKEN
  - `PositionReviewService` — injects `ExitRegimeIntegrationEngine`, `MarketRegimeService`, `ThemeStrengthService`; applies override after base decision
  - `ExitRegimeIntegrationEngineTests` — 14 tests
- Done when:
  - open-position review consumes regime/theme decay
- Codex review note:
  - Already-EXIT decisions are never overridden (more specific stops respected)
  - BULL_TREND regime protects positions even in DECAY theme (theme may lag)
  - regime/theme data fetched once per reviewAllOpenPositions() call (not per position)

## 5. Claude Implementation Cadence

Claude should implement in this order:

1. P0.1 Regime
2. P0.2 Ranking
3. P0.3 Setup
4. P0.4 Timing
5. P0.5 Risk
6. P0.6 Execution
7. P1.1 Theme
8. P1.2 Attribution
9. P1.3 Workflow rewire

For each chunk Claude must provide:
- changed files
- migration files
- tests added
- behavior change summary
- unresolved risks

## 6. Codex Review Gates

Codex reviews after each chunk:

- Gate A:
  - architecture separation is real, not renamed duplication
- Gate B:
  - no old logic bypass remains in `FinalDecisionService`
- Gate C:
  - no AI output bypasses Java hard rules
- Gate D:
  - logs and DB records are sufficient for attribution

## 7. Completion Criteria

P0 complete when:
- final entry requires explicit regime/theme/ranking/setup/timing/risk outputs
- execution trace persists upstream ids
- old final-decision blob is no longer the source of truth

P1 complete when:
- theme stage/decay is live
- trade attribution stores real setup/regime/timing metrics
- weekly learning uses attribution records

P2 complete when:
- learning is bounded
- benchmarking exists
- exit logic reacts to regime/theme decay
