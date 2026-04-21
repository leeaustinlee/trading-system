# Production Swing Redesign Implementation Spec

Last updated: 2026-04-21  
Target system: Taiwan stocks, 1-2 week swing trading  
Execution authority: Java only  
AI roles:
- Codex: candidate proposal + final veto only
- Claude: thesis / catalyst / risk flags only

## 1. Target System Overview

### 1.1 Objective

Transform the current repository into a production-grade Taiwan swing trading system with a clean decision pipeline:

`Regime -> Theme -> Ranking -> Setup -> Timing -> Risk -> Execution -> Review`

The implementation must:
- separate selection from entry timing
- separate portfolio risk from ranking
- persist decision artifacts at every layer
- support weekly PnL attribution and bounded learning

### 1.2 Trading Edge

The system edge is defined as:
- trade only in acceptable market regimes
- trade only leading themes with low decay risk
- rank relative-strength stocks inside tradable themes
- enter only on validated setups with intraday confirmation
- size by risk budget and regime, not by narrative confidence

### 1.3 Optimize For

- expectancy per trade
- lower false breakout entries
- better entry timing quality
- lower drawdown concentration
- explainable post-trade attribution

### 1.4 Explicitly Ignore

- long-form AI narrative quality
- trading frequency as a KPI
- cosmetic candidate breadth
- AI-generated execution decisions

## 2. Final Architecture (Repo-Mapped)

### Regime

- Existing:
  - `com.austin.trading.engine.MarketGateEngine`
  - `com.austin.trading.engine.HourlyGateEngine`
  - `com.austin.trading.engine.MonitorDecisionEngine`
  - `com.austin.trading.service.MarketDataService`
- New:
  - `com.austin.trading.engine.MarketRegimeEngine`
  - `com.austin.trading.service.MarketRegimeService`
  - `com.austin.trading.entity.MarketRegimeDecisionEntity`
  - `com.austin.trading.repository.MarketRegimeDecisionRepository`
- Refactor:
  - move regime classification logic out of `MarketGateEngine`
  - make hourly/monitor jobs consume regime output instead of recomputing market meaning
- Deprecate:
  - using market grade A/B/C as the main strategy router

### Theme

- Existing:
  - `com.austin.trading.engine.ThemeSelectionEngine`
  - `com.austin.trading.service.ThemeService`
- New:
  - `com.austin.trading.engine.ThemeStrengthEngine`
  - `com.austin.trading.service.ThemeStrengthService`
  - `com.austin.trading.entity.ThemeStrengthDecisionEntity`
  - `com.austin.trading.repository.ThemeStrengthDecisionRepository`
- Refactor:
  - evolve `ThemeSelectionEngine` into explicit theme strength / stage / decay logic
- Deprecate:
  - theme as only a score multiplier without stage semantics

### Ranking

- Existing:
  - `com.austin.trading.service.CandidateScanService`
  - `com.austin.trading.service.StockEvaluationService`
  - `com.austin.trading.engine.WeightedScoringEngine`
  - `com.austin.trading.engine.ConsensusScoringEngine`
- New:
  - `com.austin.trading.engine.StockRankingEngine`
  - `com.austin.trading.service.StockRankingService`
  - `com.austin.trading.entity.StockRankingSnapshotEntity`
  - `com.austin.trading.repository.StockRankingSnapshotRepository`
- Refactor:
  - keep `CandidateScanService` as data provider only
  - keep `StockEvaluationService` as evaluation storage / AI score update service
- Deprecate:
  - ranking logic inside `FinalDecisionService`

### Setup

- Existing:
  - `com.austin.trading.engine.StockEvaluationEngine`
  - `com.austin.trading.engine.StopLossTakeProfitEngine`
- New:
  - `com.austin.trading.engine.SetupEngine`
  - `com.austin.trading.service.SetupValidationService`
  - `com.austin.trading.entity.SetupDecisionEntity`
  - `com.austin.trading.repository.SetupDecisionRepository`
- Refactor:
  - move setup validation out of `StockEvaluationEngine`
- Deprecate:
  - generic `PULLBACK/BREAKOUT/REVERSAL` gating as final setup logic

### Timing

- Existing:
  - `com.austin.trading.engine.FinalDecisionEngine`
  - `com.austin.trading.scheduler.FiveMinuteMonitorJob`
- New:
  - `com.austin.trading.engine.ExecutionTimingEngine`
  - `com.austin.trading.service.ExecutionTimingService`
  - `com.austin.trading.entity.ExecutionTimingDecisionEntity`
  - `com.austin.trading.repository.ExecutionTimingDecisionRepository`
- Refactor:
  - move entry confirmation out of `FinalDecisionEngine`
- Deprecate:
  - entering based on ranking/final score without separate timing validation

### Risk

- Existing:
  - `com.austin.trading.engine.PositionSizingEngine`
  - `com.austin.trading.service.CapitalService`
  - `com.austin.trading.service.CapitalLedgerService`
- New:
  - `com.austin.trading.engine.PortfolioRiskEngine`
  - `com.austin.trading.service.PortfolioRiskService`
  - `com.austin.trading.service.ThemeExposureService`
  - `com.austin.trading.entity.PortfolioRiskDecisionEntity`
  - `com.austin.trading.repository.PortfolioRiskDecisionRepository`
  - `com.austin.trading.entity.ThemeExposureSnapshotEntity`
  - `com.austin.trading.repository.ThemeExposureSnapshotRepository`
- Refactor:
  - keep `PositionSizingEngine` as share-sizing subcomponent only
- Deprecate:
  - risk checks embedded in `FinalDecisionService`

### Execution

- Existing:
  - `com.austin.trading.service.FinalDecisionService`
  - `com.austin.trading.engine.FinalDecisionEngine`
- New:
  - `com.austin.trading.service.ExecutionDecisionService`
  - `com.austin.trading.engine.ExecutionDecisionEngine`
  - `com.austin.trading.dto.internal.ExecutionDecisionContext`
  - `com.austin.trading.entity.ExecutionDecisionLogEntity`
  - `com.austin.trading.repository.ExecutionDecisionLogRepository`
- Refactor:
  - convert `FinalDecisionService` into orchestration wrapper over the new pipeline
- Deprecate:
  - mixed scoring + timing + risk + AI readiness in one service

### Review

- Existing:
  - `com.austin.trading.service.TradeReviewService`
  - `com.austin.trading.engine.TradeReviewEngine`
  - `com.austin.trading.service.StrategyRecommendationService`
  - `com.austin.trading.engine.StrategyRecommendationEngine`
- New:
  - `com.austin.trading.engine.TradeAttributionEngine`
  - `com.austin.trading.service.TradeAttributionService`
  - `com.austin.trading.service.WeeklyLearningService`
  - `com.austin.trading.entity.TradeAttributionEntity`
  - `com.austin.trading.repository.TradeAttributionRepository`
- Refactor:
  - make review consume stored execution metadata instead of fallbacks
- Deprecate:
  - review inference when setup/regime/timing data is missing

## 3. Module Specifications

### 3.1 MarketRegimeEngine

#### Responsibility

- classify the current market into:
  - `BULL_TREND`
  - `RANGE_CHOP`
  - `WEAK_DOWNTREND`
  - `PANIC_VOLATILITY`
- output trade permission, risk multiplier, and allowed setups

#### Input Model

Create `com.austin.trading.dto.internal.MarketRegimeInput`:

```java
public record MarketRegimeInput(
        LocalDate tradingDate,
        LocalDateTime evaluatedAt,
        String marketGrade,
        String marketPhase,
        BigDecimal tsmcTrendScore,
        BigDecimal breadthPositiveRatio,
        BigDecimal breadthNegativeRatio,
        BigDecimal leadersStrongRatio,
        BigDecimal indexDistanceFromMa10Pct,
        BigDecimal indexDistanceFromMa20Pct,
        BigDecimal intradayVolatilityPct,
        boolean washoutRebound,
        boolean nearHighNotBreak,
        boolean blowoffSignal
) {}
```

#### Output Model

Create `com.austin.trading.dto.internal.MarketRegimeDecision`:

```java
public record MarketRegimeDecision(
        LocalDate tradingDate,
        LocalDateTime evaluatedAt,
        String regimeType,
        String marketGrade,
        boolean tradeAllowed,
        BigDecimal riskMultiplier,
        List<String> allowedSetupTypes,
        String summary,
        String reasonsJson
) {}
```

#### Key Methods

```java
public MarketRegimeDecision evaluate(MarketRegimeInput input);
public boolean isTradeAllowed(MarketRegimeDecision decision);
public BigDecimal resolveRiskMultiplier(String regimeType);
```

#### Decision Rules

- `BULL_TREND`
  - `marketGrade = A`
  - `breadthPositiveRatio >= regime.bull.min_breadth_ratio`
  - `leadersStrongRatio >= regime.bull.min_leaders_ratio`
  - `indexDistanceFromMa10Pct >= 0`
  - `indexDistanceFromMa20Pct >= 0`
- `RANGE_CHOP`
  - broad market not weak enough for downtrend
  - `abs(indexDistanceFromMa10Pct) <= regime.range.max_ma_distance_pct`
  - breadth mixed
- `WEAK_DOWNTREND`
  - market below 20MA
  - negative breadth elevated
  - leaders weak
- `PANIC_VOLATILITY`
  - `intradayVolatilityPct >= regime.panic.volatility_threshold`
  - or `breadthNegativeRatio >= regime.panic.min_negative_breadth_ratio`

#### Config Parameters

- `regime.bull.min_breadth_ratio`
- `regime.bull.min_leaders_ratio`
- `regime.range.max_ma_distance_pct`
- `regime.weakdown.min_negative_breadth_ratio`
- `regime.panic.volatility_threshold`
- `regime.panic.min_negative_breadth_ratio`
- `regime.risk_multiplier.bull`
- `regime.risk_multiplier.range`
- `regime.risk_multiplier.weakdown`
- `regime.risk_multiplier.panic`

#### DB Storage

Create table `market_regime_decision`:
- `id`
- `trading_date`
- `evaluated_at`
- `regime_type`
- `market_grade`
- `trade_allowed`
- `risk_multiplier`
- `allowed_setup_types_json`
- `summary`
- `reasons_json`
- `payload_json`
- `version`
- `created_at`

Index:
- `(trading_date, evaluated_at desc)`

#### Logging / Audit

- `rule_hits_json`
- `input_snapshot_json`
- `market_snapshot_id`
- `version`

#### Dependencies

- `MarketDataService`
- `TradingStateService`
- existing market snapshot repositories

### 3.2 ThemeStrengthEngine

#### Responsibility

- evaluate theme tradability for the current regime
- classify theme stage and decay risk

#### Input Model

Create `ThemeStrengthInput`:

```java
public record ThemeStrengthInput(
        LocalDate tradingDate,
        String themeTag,
        BigDecimal marketBehaviorScore,
        BigDecimal breadthScore,
        BigDecimal leaderRatio,
        BigDecimal averagePriceChangePct,
        BigDecimal claudeHeatScore,
        BigDecimal claudeContinuationScore,
        String catalystType,
        String catalystStrength,
        boolean hasRiskFlag
) {}
```

#### Output Model

Create `ThemeStrengthDecision`:

```java
public record ThemeStrengthDecision(
        LocalDate tradingDate,
        String themeTag,
        BigDecimal strengthScore,
        String themeStage,
        String catalystType,
        boolean tradable,
        BigDecimal decayRisk,
        String reasonsJson
) {}
```

#### Key Methods

```java
public List<ThemeStrengthDecision> evaluateAll(LocalDate tradingDate, MarketRegimeDecision regime);
public Optional<ThemeStrengthDecision> findLeadingTheme(LocalDate tradingDate, String symbol);
```

#### Decision Rules

- `strengthScore` formula:
  - `marketBehavior * theme.weight.market_behavior`
  - `+ claudeHeat * theme.weight.heat`
  - `+ claudeContinuation * theme.weight.continuation`
  - `+ breadthScore * theme.weight.breadth`
- `themeStage`
  - `EARLY_EXPANSION`
  - `MID_TREND`
  - `LATE_EXTENSION`
  - `DECAY`
- `tradable=false` when:
  - `decayRisk > theme.decay.max_allowed`
  - `strengthScore < theme.tradable.min_strength`
  - `hasRiskFlag=true` and regime is not `BULL_TREND`

#### Config Parameters

- `theme.weight.market_behavior`
- `theme.weight.heat`
- `theme.weight.continuation`
- `theme.weight.breadth`
- `theme.tradable.min_strength`
- `theme.decay.max_allowed`
- `theme.stage.late_extension_threshold`

#### DB Storage

Create `theme_strength_decision`:
- `id`
- `trading_date`
- `theme_tag`
- `strength_score`
- `theme_stage`
- `catalyst_type`
- `tradable`
- `decay_risk`
- `reasons_json`
- `payload_json`
- `created_at`

Index:
- `(trading_date, theme_tag)`

#### Logging / Audit

- `component_scores_json`
- `claude_research_refs_json`
- `version`

#### Dependencies

- `ThemeSelectionEngine`
- `ThemeService`
- AI research persistence

### 3.3 StockRankingService

#### Responsibility

- rank candidates after regime/theme filtering
- output only symbols eligible for setup validation

#### Input Model

Create `RankCandidateInput`:

```java
public record RankCandidateInput(
        LocalDate tradingDate,
        String symbol,
        BigDecimal javaStructureScore,
        BigDecimal claudeScore,
        BigDecimal codexScore,
        BigDecimal finalRankScore,
        BigDecimal relativeStrengthScore,
        BigDecimal themeStrengthScore,
        String themeTag,
        boolean codexVetoed,
        boolean inCooldown,
        boolean alreadyHeld
) {}
```

#### Output Model

Create `RankedCandidate`:

```java
public record RankedCandidate(
        LocalDate tradingDate,
        String symbol,
        BigDecimal selectionScore,
        BigDecimal relativeStrengthScore,
        BigDecimal themeStrengthScore,
        BigDecimal thesisScore,
        String themeTag,
        boolean vetoed,
        boolean eligibleForSetup,
        String rejectionReason,
        String scoreBreakdownJson
) {}
```

#### Key Methods

```java
public List<RankedCandidate> rank(LocalDate tradingDate, MarketRegimeDecision regime);
public List<RankedCandidate> topCandidates(LocalDate tradingDate, MarketRegimeDecision regime, int topN);
```

#### Decision Rules

- hard reject if:
  - `codexVetoed=true`
  - `inCooldown=true`
  - `alreadyHeld=true`
  - no tradable theme
- `selectionScore` formula:
  - `relativeStrengthScore * ranking.weight.rs`
  - `+ themeStrengthScore * ranking.weight.theme`
  - `+ javaStructureScore * ranking.weight.java_structure`
  - `+ claudeScore * ranking.weight.thesis`
- sort descending
- keep top `ranking.top_n`

#### Config Parameters

- `ranking.weight.rs`
- `ranking.weight.theme`
- `ranking.weight.java_structure`
- `ranking.weight.thesis`
- `ranking.top_n`
- `ranking.min_selection_score`

#### DB Storage

Create `stock_ranking_snapshot`:
- `id`
- `trading_date`
- `symbol`
- `selection_score`
- `relative_strength_score`
- `theme_strength_score`
- `thesis_score`
- `theme_tag`
- `vetoed`
- `eligible_for_setup`
- `rejection_reason`
- `score_breakdown_json`
- `created_at`

Index:
- `(trading_date, selection_score desc)`
- `(trading_date, symbol)`

#### Logging / Audit

- filtered candidate count
- rejection reasons
- score formula version

#### Dependencies

- `CandidateScanService`
- `StockEvaluationService`
- `CooldownService`
- `PositionRepository`
- `ThemeStrengthService`

### 3.4 SetupEngine

#### Responsibility

- validate only 3 supported setups:
  - `BREAKOUT_CONTINUATION`
  - `PULLBACK_CONFIRMATION`
  - `EVENT_SECOND_LEG`

#### Input Model

Create `SetupEvaluationInput`:

```java
public record SetupEvaluationInput(
        RankedCandidate candidate,
        MarketRegimeDecision regime,
        ThemeStrengthDecision themeDecision,
        BigDecimal currentPrice,
        BigDecimal prevClose,
        BigDecimal ma5,
        BigDecimal ma10,
        BigDecimal baseHigh,
        BigDecimal baseLow,
        BigDecimal recentSwingHigh,
        BigDecimal recentSwingLow,
        BigDecimal avgVolume5,
        BigDecimal currentVolume,
        int consolidationDays,
        boolean eventDriven
) {}
```

#### Output Model

Create `SetupDecision`:

```java
public record SetupDecision(
        LocalDate tradingDate,
        String symbol,
        String setupType,
        boolean valid,
        BigDecimal entryZoneLow,
        BigDecimal entryZoneHigh,
        BigDecimal idealEntryPrice,
        BigDecimal invalidationPrice,
        BigDecimal initialStopPrice,
        BigDecimal takeProfit1Price,
        BigDecimal takeProfit2Price,
        String trailingMode,
        Integer holdingWindowDays,
        String rejectionReason,
        String payloadJson
) {}
```

#### Key Methods

```java
public List<SetupDecision> evaluate(List<RankedCandidate> candidates, MarketRegimeDecision regime);
public SetupDecision evaluateOne(SetupEvaluationInput input);
```

#### Decision Rules

- `BREAKOUT_CONTINUATION`
  - regime must allow breakout
  - theme tradable
  - current price near or above base high
  - current volume >= `setup.breakout.volume_multiplier * avgVolume5`
- `PULLBACK_CONFIRMATION`
  - regime bull or range
  - current price at or near ma5/ma10 support
  - current price above invalidation support
  - pullback not too deep
- `EVENT_SECOND_LEG`
  - eventDriven = true
  - consolidationDays in configured range
  - range not broken down

#### Config Parameters

- `setup.breakout.volume_multiplier`
- `setup.breakout.base_lookback_days`
- `setup.pullback.max_depth_pct`
- `setup.event.min_consolidation_days`
- `setup.event.max_consolidation_days`
- `setup.stop.breakout_pct`
- `setup.stop.pullback_pct`
- `setup.stop.event_pct`
- `setup.tp1.default_pct`
- `setup.tp2.default_pct`

#### DB Storage

Create `setup_decision_log`:
- `id`
- `trading_date`
- `symbol`
- `setup_type`
- `valid`
- `entry_zone_low`
- `entry_zone_high`
- `ideal_entry_price`
- `invalidation_price`
- `initial_stop_price`
- `take_profit_1_price`
- `take_profit_2_price`
- `trailing_mode`
- `holding_window_days`
- `rejection_reason`
- `payload_json`
- `created_at`

#### Logging / Audit

- `support_resistance_json`
- `volume_check_json`
- `rule_passes_json`

#### Dependencies

- `StopLossTakeProfitEngine`
- ranking output
- current quote data

### 3.5 ExecutionTimingEngine

#### Responsibility

- confirm whether a valid setup can be entered now
- reject stale, late, weak intraday entries

#### Input Model

Create `IntradaySignalContext`:

```java
public record IntradaySignalContext(
        LocalDate tradingDate,
        LocalDateTime quoteTime,
        BigDecimal openPrice,
        BigDecimal currentPrice,
        BigDecimal dayHigh,
        BigDecimal dayLow,
        BigDecimal breakoutLevel,
        BigDecimal supportLevel,
        BigDecimal currentVolume,
        BigDecimal avgMinuteVolume,
        BigDecimal cumulativeVolume,
        LocalDateTime firstTriggerAt,
        boolean longUpperShadow,
        boolean longBlackBar,
        boolean reclaimedSupport
) {}
```

#### Output Model

Create `ExecutionTimingDecision`:

```java
public record ExecutionTimingDecision(
        LocalDate tradingDate,
        String symbol,
        String setupType,
        boolean canEnterNow,
        String confirmationType,
        BigDecimal entryPrice,
        BigDecimal idealEntryPrice,
        BigDecimal delayPct,
        BigDecimal timingScore,
        boolean staleSignal,
        String skipReason,
        String auditJson
) {}
```

#### Key Methods

```java
public ExecutionTimingDecision evaluate(SetupDecision setup, MarketRegimeDecision regime, IntradaySignalContext ctx);
public ExecutionTimingDecision evaluateBreakout(SetupDecision setup, MarketRegimeDecision regime, IntradaySignalContext ctx);
public ExecutionTimingDecision evaluatePullback(SetupDecision setup, MarketRegimeDecision regime, IntradaySignalContext ctx);
public ExecutionTimingDecision evaluateEventSecondLeg(SetupDecision setup, MarketRegimeDecision regime, IntradaySignalContext ctx);
public boolean isStaleSignal(SetupDecision setup, IntradaySignalContext ctx);
public boolean isChaseTooFar(SetupDecision setup, IntradaySignalContext ctx);
```

#### Decision Rules

- breakout confirmation:
  - `currentPrice >= entryZoneHigh`
  - hold above breakout level for configured confirm minutes
  - no retrace below breakout level by more than `timing.breakout.max_retrace_pct`
  - no long upper shadow
  - breakout volume confirmation required
- pullback confirmation:
  - `currentPrice` inside pullback zone
  - support not broken
  - `reclaimedSupport=true`
  - `longBlackBar=false`
- delay tolerance:
  - `delayPct = (entryPrice - idealEntryPrice) / idealEntryPrice * 100`
  - reject if above setup-specific max delay
- stale signal:
  - reject if `firstTriggerAt` older than setup-specific stale threshold
- intraday window:
  - allow default `09:10` to `10:20`
  - allow `EVENT_SECOND_LEG` until `10:50` only in `BULL_TREND`
- skip conditions:
  - stale
  - chase too far
  - long black bar
  - long upper shadow
  - time window closed
  - regime not tradable

#### Config Parameters

- `timing.entry.start_time`
- `timing.entry.end_time`
- `timing.entry.end_time.event_second_leg`
- `timing.breakout.confirm_minutes`
- `timing.breakout.max_retrace_pct`
- `timing.breakout.max_delay_pct`
- `timing.pullback.max_delay_pct`
- `timing.event.max_delay_pct`
- `timing.stale_minutes.breakout`
- `timing.stale_minutes.pullback`
- `timing.stale_minutes.event`

#### DB Storage

Create `execution_timing_log`:
- `id`
- `trading_date`
- `symbol`
- `setup_type`
- `quote_time`
- `can_enter_now`
- `confirmation_type`
- `entry_price`
- `ideal_entry_price`
- `delay_pct`
- `timing_score`
- `stale_signal`
- `skip_reason`
- `audit_json`
- `created_at`

#### Logging / Audit

- `trigger_time`
- `price_context_json`
- `volume_context_json`
- `confirmation_trace_json`

#### Dependencies

- setup output
- market intraday quote data

### 3.6 PortfolioRiskEngine

#### Responsibility

- enforce account-level and portfolio-level risk
- compute allowed size after all hard checks pass

#### Input Model

Create `PortfolioRiskInput`:

```java
public record PortfolioRiskInput(
        MarketRegimeDecision regime,
        ThemeStrengthDecision themeDecision,
        SetupDecision setupDecision,
        ExecutionTimingDecision timingDecision,
        CapitalSummaryResponse capitalSummary,
        BigDecimal perShareRisk,
        BigDecimal dailyRealizedPnl,
        BigDecimal weeklyDrawdownPct,
        int openPositionsCount,
        BigDecimal existingThemeExposurePct,
        BigDecimal proposedThemeExposurePct
) {}
```

#### Output Model

Create `PortfolioRiskDecision`:

```java
public record PortfolioRiskDecision(
        LocalDate tradingDate,
        String symbol,
        boolean allowTrade,
        BigDecimal riskAmount,
        BigDecimal maxPositionAmount,
        BigDecimal positionSizeAmount,
        BigDecimal positionSizeShares,
        String blockReason,
        String auditJson
) {}
```

#### Key Methods

```java
public PortfolioRiskDecision evaluate(PortfolioRiskInput input);
public BigDecimal computeRiskAmount(PortfolioRiskInput input);
public BigDecimal computePositionShares(BigDecimal riskAmount, BigDecimal perShareRisk);
```

#### Decision Rules

- block if:
  - `regime.tradeAllowed = false`
  - `dailyRealizedPnl <= -risk.daily_loss_cap_pct * equity`
  - `weeklyDrawdownPct >= risk.weekly_drawdown_pause_pct`
  - open positions >= regime max
  - theme exposure exceeds configured cap
  - available cash insufficient
- size:
  - `baseRisk = totalEquity * risk.per_trade_pct`
  - `adjustedRisk = baseRisk * regime.riskMultiplier`
  - apply timing quality multiplier
  - `shares = adjustedRisk / perShareRisk`
  - cap by `portfolio.max_position_pct_of_equity`

#### Config Parameters

- `risk.per_trade_pct`
- `risk.daily_loss_cap_pct`
- `risk.weekly_drawdown_pause_pct`
- `portfolio.max_open_positions.bull`
- `portfolio.max_open_positions.range`
- `portfolio.max_open_positions.weakdown`
- `portfolio.same_theme_exposure_pct`
- `portfolio.max_position_pct_of_equity`
- `portfolio.timing_size_multiplier.good`
- `portfolio.timing_size_multiplier.ok`
- `portfolio.timing_size_multiplier.bad`

#### DB Storage

Create `portfolio_risk_log`:
- `id`
- `trading_date`
- `symbol`
- `allow_trade`
- `risk_amount`
- `max_position_amount`
- `position_size_amount`
- `position_size_shares`
- `block_reason`
- `audit_json`
- `created_at`

Create `theme_exposure_snapshot`:
- `id`
- `trading_date`
- `theme_tag`
- `exposure_pct`
- `position_count`
- `created_at`

#### Logging / Audit

- `cash_balance`
- `available_cash`
- `equity`
- `daily_pnl`
- `weekly_drawdown`
- `theme_exposure_before_after`

#### Dependencies

- `CapitalService`
- `PositionSizingEngine`
- `PositionRepository`
- `PnlService`

### 3.7 ExecutionDecisionService

#### Responsibility

- aggregate the outputs of all prior layers
- emit final `ENTER / SKIP / EXIT / WEAKEN`

#### Input Model

Create `ExecutionDecisionContext`:

```java
public record ExecutionDecisionContext(
        MarketRegimeDecision regime,
        ThemeStrengthDecision themeDecision,
        RankedCandidate rankedCandidate,
        SetupDecision setupDecision,
        ExecutionTimingDecision timingDecision,
        PortfolioRiskDecision riskDecision,
        boolean codexVetoed,
        String codexVetoReason
) {}
```

#### Output Model

Create `ExecutionDecisionResult`:

```java
public record ExecutionDecisionResult(
        LocalDate tradingDate,
        String symbol,
        String decision,
        String setupType,
        BigDecimal entryPrice,
        BigDecimal stopPrice,
        BigDecimal takeProfit1Price,
        BigDecimal takeProfit2Price,
        BigDecimal positionSizeAmount,
        BigDecimal positionSizeShares,
        String decisionReason,
        String decisionTraceJson
) {}
```

#### Key Methods

```java
public List<ExecutionDecisionResult> decideEntries(LocalDate tradingDate);
public ExecutionDecisionResult decideOne(ExecutionDecisionContext context);
public void persistDecision(ExecutionDecisionResult result);
```

#### Decision Rules

- order:
  1. risk hard block
  2. codex veto
  3. regime block
  4. theme block
  5. setup invalid
  6. timing invalid
  7. size invalid
  8. enter
- conflict resolution:
  - Java hard rule always wins
  - Codex veto can block only
  - Claude thesis cannot force entry

#### Config Parameters

- `execution.max_entries_per_day`
- `execution.require_codex_clear`
- `execution.persist_trace`

#### DB Storage

Create `execution_decision_log`:
- `id`
- `trading_date`
- `symbol`
- `decision`
- `setup_type`
- `entry_price`
- `stop_price`
- `take_profit_1_price`
- `take_profit_2_price`
- `position_size_amount`
- `position_size_shares`
- `decision_reason`
- `decision_trace_json`
- `regime_decision_id`
- `theme_decision_id`
- `ranking_snapshot_id`
- `setup_decision_id`
- `timing_decision_id`
- `risk_decision_id`
- `created_at`

#### Logging / Audit

- upstream decision ids
- codex veto flag
- block reason chain

#### Dependencies

- all previous layers
- `AiTaskService`
- `FinalDecisionRepository` if legacy summary is still needed

### 3.8 TradeReview / Attribution Engine

#### Responsibility

- compute attribution from actual entry/exit metadata
- feed weekly learning with structured failure causes

#### Input Model

Create `TradeAttributionInput`:

```java
public record TradeAttributionInput(
        Long positionId,
        String symbol,
        String setupType,
        String regimeType,
        String themeStage,
        BigDecimal actualEntryPrice,
        BigDecimal idealEntryPrice,
        BigDecimal actualExitPrice,
        BigDecimal stopPrice,
        BigDecimal takeProfit1Price,
        BigDecimal riskAmount,
        BigDecimal actualAllocatedAmount,
        BigDecimal mfePct,
        BigDecimal maePct,
        Integer holdingDays,
        String exitReason
) {}
```

#### Output Model

Create `TradeAttributionResult`:

```java
public record TradeAttributionResult(
        Long positionId,
        String symbol,
        String setupType,
        String regimeType,
        String themeStage,
        BigDecimal idealEntryPrice,
        BigDecimal delayPct,
        BigDecimal mfePct,
        BigDecimal maePct,
        String timingQuality,
        String exitQuality,
        String sizingQuality,
        String primaryFailure,
        String reviewGrade,
        String attributionJson
) {}
```

#### Key Methods

```java
public TradeAttributionResult evaluate(TradeAttributionInput input);
public BigDecimal computeDelayPct(BigDecimal idealEntryPrice, BigDecimal actualEntryPrice);
public String classifyTimingQuality(BigDecimal delayPct, BigDecimal maePct);
public String classifyExitQuality(BigDecimal mfePct, BigDecimal realizedPct);
public String classifySizingQuality(BigDecimal riskAmount, BigDecimal actualAllocatedAmount);
```

#### Decision Rules

- `delayPct`:
  - `(actualEntryPrice - idealEntryPrice) / idealEntryPrice * 100`
- `timingQuality`
  - `GOOD` if `delayPct <= review.timing.good_delay_pct`
  - `OK` if `delayPct <= review.timing.bad_delay_pct`
  - `BAD` otherwise
- `exitQuality`
  - compare realized pnl pct against `mfePct`
  - `BAD_GIVEBACK` if giveback exceeds configured threshold
- `sizingQuality`
  - compare actual allocated risk with approved risk budget
- `primaryFailure`
  - one of:
    - `SELECTION`
    - `TIMING`
    - `EXIT`
    - `SIZING`
    - `REGIME`
    - `NO_FAILURE`

#### Config Parameters

- `review.timing.good_delay_pct`
- `review.timing.bad_delay_pct`
- `review.exit.giveback_bad_pct`
- `review.sizing.oversize_bad_pct`
- `review.min_sample_size`

#### DB Storage

Create `trade_attribution`:
- `id`
- `position_id`
- `symbol`
- `setup_type`
- `regime_type`
- `theme_stage`
- `ideal_entry_price`
- `delay_pct`
- `mfe_pct`
- `mae_pct`
- `timing_quality`
- `exit_quality`
- `sizing_quality`
- `primary_failure`
- `review_grade`
- `attribution_json`
- `execution_decision_log_id`
- `created_at`

#### Logging / Audit

- `attribution_version`
- `source_log_ids_json`

#### Dependencies

- `TradeReviewService`
- `PositionRepository`
- execution decision logs

## 4. Execution Timing Engine Detailed Rules

### 4.1 Breakout Confirmation Rules

- setup types:
  - `BREAKOUT_CONTINUATION`
  - `EVENT_SECOND_LEG`
- required:
  - `currentPrice >= setup.entryZoneHigh`
  - breakout held above `entryZoneHigh` for `timing.breakout.confirm_minutes`
  - retrace from breakout high is within `timing.breakout.max_retrace_pct`
  - `currentVolume >= avgMinuteVolume * timing.breakout.volume_multiplier`
  - `longUpperShadow = false`
- skip when:
  - breakout wick-only move
  - long upper shadow
  - first trigger older than stale threshold
  - delay exceeds max

### 4.2 Pullback Confirmation Rules

- setup type:
  - `PULLBACK_CONFIRMATION`
- required:
  - `currentPrice >= setup.entryZoneLow`
  - `currentPrice <= setup.entryZoneHigh`
  - `currentPrice > setup.invalidationPrice`
  - `reclaimedSupport = true`
  - `longBlackBar = false`
- skip when:
  - support breaks intraday
  - reclaim failed
  - delay too large from ideal reclaim price

### 4.3 Delay Tolerance Logic

- compute:
  - `delayPct = (entryPrice - idealEntryPrice) / idealEntryPrice * 100`
- thresholds:
  - breakout uses `timing.breakout.max_delay_pct`
  - pullback uses `timing.pullback.max_delay_pct`
  - event uses `timing.event.max_delay_pct`
- reject if threshold exceeded

### 4.4 Stale Signal Detection

- breakout stale:
  - `Duration.between(firstTriggerAt, quoteTime) > timing.stale_minutes.breakout`
- pullback stale:
  - `Duration.between(firstTriggerAt, quoteTime) > timing.stale_minutes.pullback`
- event stale:
  - `Duration.between(firstTriggerAt, quoteTime) > timing.stale_minutes.event`

### 4.5 Intraday Time Window Rules

- default allowed entry window:
  - `09:10` to `10:20`
- `EVENT_SECOND_LEG` special window:
  - allow until `10:50` only in `BULL_TREND`
- after hard cutoff:
  - always skip

### 4.6 Skip Conditions Even if Score Is High

- `staleSignal = true`
- `delayPct > max allowed`
- long upper shadow breakout
- long black bar pullback reclaim
- current time outside entry window
- regime not tradable
- codex veto
- portfolio risk blocked

### 4.7 Method-Level Decision Flow

1. reject if setup invalid
2. reject if regime disallows the setup type
3. reject if current time outside allowed window
4. route by setup type:
   - breakout
   - pullback
   - event second leg
5. compute `idealEntryPrice`
6. compute `delayPct`
7. check stale
8. check over-chase
9. emit `canEnterNow=true` only when all checks pass

## 5. Final Decision Flow

### Input

- regime
- theme
- ranking
- setup
- timing
- risk
- codex veto

### Output

- `ENTER`
- `SKIP`
- `EXIT`
- `WEAKEN`

### Decision Priority

1. `PortfolioRiskDecision.allowTrade = false` -> `SKIP`
2. `codexVetoed = true` -> `SKIP`
3. `regime.tradeAllowed = false` -> `SKIP`
4. `themeDecision.tradable = false` -> `SKIP`
5. `setupDecision.valid = false` -> `SKIP`
6. `timingDecision.canEnterNow = false` -> `SKIP`
7. `riskDecision.positionSizeShares <= 0` -> `SKIP`
8. otherwise -> `ENTER`

### Conflict Resolution

- Java hard rules override all other inputs
- Codex veto can only block
- Claude thesis can influence theme/risk context only
- ranking score cannot override timing/risk failure

### Veto Behavior

- veto applied at execution layer only
- veto must be persisted in `decisionTraceJson`
- veto should not erase ranking/setup history

## 6. Trade Attribution System

### Required Fields

- `setupType`
- `regimeType`
- `idealEntryPrice`
- `delayPct`
- `mfePct`
- `maePct`
- `timingQuality`
- `exitQuality`
- `sizingQuality`
- `themeStage`

### Calculation Rules

- `setupType`
  - from `SetupDecision`
- `regimeType`
  - from `MarketRegimeDecision`
- `idealEntryPrice`
  - from `ExecutionTimingDecision`
- `delayPct`
  - formula defined above
- `mfePct`
  - max favorable excursion against actual entry
- `maePct`
  - max adverse excursion against actual entry
- `timingQuality`
  - delay and early adverse move
- `exitQuality`
  - compare realized pnl against `mfePct`
- `sizingQuality`
  - compare approved risk vs actual allocated size
- `themeStage`
  - from `ThemeStrengthDecision`

### Storage

- `trade_attribution`
- existing `trade_review`
- optional foreign keys or stored ids to upstream decision logs

### Weekly Learning Feed

- aggregate by:
  - `setupType`
  - `regimeType`
  - `themeStage`
  - `timingQuality`
  - `exitQuality`
- only bounded parameter updates allowed

## 7. Data & DB Design

### New Tables

- `market_regime_decision`
- `theme_strength_decision`
- `stock_ranking_snapshot`
- `setup_decision_log`
- `execution_timing_log`
- `portfolio_risk_log`
- `theme_exposure_snapshot`
- `execution_decision_log`
- `trade_attribution`

### Existing Tables Reused

- `market_snapshot`
- `theme_snapshot`
- `candidate_stock`
- `stock_evaluation`
- `final_decision`
- `position`
- `daily_pnl`
- `trade_review`
- `strategy_recommendation`
- `capital_ledger`

### Indexing

- all symbol-scoped logs: `(trading_date, symbol)`
- ranking: `(trading_date, selection_score desc)`
- review/attribution: `(position_id)`
- regime/theme snapshots: `(trading_date)`

### Logging Strategy

- each layer writes one structured record
- final execution stores upstream ids
- summary strings are secondary

## 8. Refactor Plan

### Step 1

- Extract market regime logic from:
  - `MarketGateEngine`
  - `HourlyGateEngine`
  - `MonitorDecisionEngine`
- Into:
  - `MarketRegimeEngine`
  - `MarketRegimeService`

### Step 2

- Extract ranking logic from `FinalDecisionService`
- Keep `CandidateScanService` as candidate data provider only

### Step 3

- Create `SetupEngine`
- Move setup validation out of `StockEvaluationEngine`

### Step 4

- Create `ExecutionTimingEngine`
- Remove entry confirmation from `FinalDecisionEngine`

### Step 5

- Create `PortfolioRiskEngine`
- Extract max positions, same-theme caps, cash checks, and size logic out of `FinalDecisionService`

### Step 6

- Replace final decision core with `ExecutionDecisionService`
- Keep legacy API endpoints stable via adapter methods if needed

### Step 7

- Extend position open/close flow to persist:
  - setup id
  - timing id
  - risk id
  - execution id

### Step 8

- Rewrite review path:
  - `TradeReviewService`
  - `TradeReviewEngine`
  - `StrategyRecommendationService`
- Make them consume actual attribution fields

## 9. Simplification Plan

### Remove / Reduce

- `A+ only` as the main entry gate
- ranking logic inside `FinalDecisionService`
- duplicate stop/tp defaults in multiple services
- placeholder watchlist fields with fake defaults

### Ignore for Execution

- long Claude narrative text
- AI prose with no structured catalyst/risk output
- cosmetic multi-score layering that does not affect expectancy

### Deprecate Configs

- configs that only support old final-rank-only gating
- duplicate veto checks across multiple layers

## 10. Implementation Roadmap

### P0

- `MarketRegimeEngine`
- `StockRankingService`
- `SetupEngine`
- `ExecutionTimingEngine`
- `PortfolioRiskEngine`
- `ExecutionDecisionService`
- structured decision log tables

Impact on PnL:
- highest

Complexity:
- medium-high

Dependencies:
- existing quote/data prep pipeline

### P1

- `ThemeStrengthEngine`
- attribution redesign
- weekly learning rewrite
- workflow and scheduler rewiring

Impact on PnL:
- high

Complexity:
- medium-high

Dependencies:
- P0 decision artifacts available

### P2

- bounded parameter tuning
- benchmark-relative analytics
- enhanced theme decay model
- better exit/risk interaction with regime decay

Impact on PnL:
- medium

Complexity:
- medium

Dependencies:
- enough live/review history

## 11. Final Validation

### Actual Alpha Source

- regime-filtered trading of leading themes and relative-strength stocks
- with setup and intraday timing confirmation

### Highest Profitability Impact Module

- `ExecutionTimingEngine`

### Biggest Remaining Risk

- preserving too much old score/veto/final-rank behavior and wrapping it in new names

### Must Not Be Over-Engineered

- AI narrative layer
- watchlist cosmetics
- weekly parameter auto-tuning
- excessive scoring dimensions
