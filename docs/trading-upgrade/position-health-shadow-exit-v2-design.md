# Position Health / Shadow Exit v2 Design

## Safety contract

- Shadow only.
- No production BUY path change.
- No auto-ordering.
- No true-position auto SELL.
- Formal output is alert tier / diagnosis / review recommendation only.

## PositionHealthSignal

Fields:

- symbol
- tradingDate
- currentPrice
- entryPrice
- unrealizedPnlPct
- ma5, ma10, ma20
- ma5SlopePct, ma10SlopePct, ma20SlopePct
- isBullishMaStack: ma5 > ma10 > ma20
- priceVsMa5Pct, priceVsMa10Pct, priceVsMa20Pct
- previousSwingLow
- distanceToPreviousLowPct
- atr14
- volumeRatio5d
- pullbackVolumeQuality: SHRINKING / NORMAL / EXPANDING / DATA_GAP
- relativeStrength5dVsTaiex
- relativeStrength10dVsTaiex
- themeStrengthTag
- chipFlowSignal: FOREIGN_BUY / FOREIGN_SELL / TRUST_BUY / TRUST_SELL / MIXED / DATA_GAP
- dataGaps: list

## Health tiers

### HOLD

Use when:

- price >= ma5, or price between ma5 and ma10 with shrinking pullback volume
- previous low intact
- no long-black volume expansion
- relative strength remains positive or neutral
- theme remains active

Meaning: normal pullback / healthy consolidation; do not exit solely because trailing stop is close.

### SOFT_WARNING

Use when:

- price touches or slightly breaks trailing stop, but still above ma10 or above previous low
- volume is not expanding on the pullback
- theme/chip flow is not clearly deteriorating

Meaning: alert human, do not force exit.

### REDUCE_REVIEW

Use when:

- price breaks ma5 and cannot reclaim, but ma10/previous low still intact
- volume expands mildly
- relative strength turns negative vs TAIEX or same-theme peers

Meaning: consider partial reduce, especially if already profitable or MFE was high.

### EXIT_REVIEW

Use when:

- price breaks ma10 or previous swing low
- pullback volume expands
- theme heat weakens or chip flow reverses
- current exit rule says STOP/TRAILING but structure confirms deterioration

Meaning: human-confirmed exit candidate.

### HARD_EXIT_ALERT

Use when:

- gap-down / breakdown below previous low
- long black candle with abnormal volume
- ma5 < ma10 deterioration and price below ma10
- stop loss breached plus structure broken

Meaning: urgent human review; still no auto-sell unless future explicit policy says so.

## Shadow exit rule comparison

For every paper_trade close or position review event, persist/read report comparing:

1. actual rule
2. trailing stop only
3. trailing stop + 5MA confirmation
4. trailing stop + previous-low confirmation
5. ATR stop + structure confirmation
6. staged profit taking
   - +5% reduce 1/3
   - +8~10% activate trailing
   - below 5MA but above 10MA = observe
   - below 10MA or previous low = exit review

Each comparison should include:

- wouldExit: true/false
- exitTier: HOLD / SOFT_WARNING / REDUCE_REVIEW / EXIT_REVIEW / HARD_EXIT_ALERT
- hypotheticalExitPrice
- hypotheticalPnlPct
- actualPnlPct
- pnlDiffPct
- dataGaps
- conclusion: BETTER / WORSE / SAME / DATA_GAP

## Immediate P0 implementation priorities

1. Fix/flag price-plan anomalies before trusting exit tests:
   - target1 <= entry
   - target2 <= target1
   - TP1_HIT with pnlPct <= 0
   - negative/zero RR

2. Add position health snapshot for currently open positions using live quote + daily technicals.

3. Add closed-trade exit rule comparison only where daily bars exist; otherwise DATA_GAP.

4. Add APIs:
   - GET `/api/backtest/diagnosis/price-plan-sanity?days=60`
   - GET `/api/backtest/diagnosis/theme-propagation?days=60`
   - GET `/api/backtest/diagnosis/exit-rule-comparison?days=60`
   - GET `/api/portfolio/health-v2`

## Non-goals

- Do not auto-sell true positions.
- Do not allow shadow exit to change FinalDecision decision.
- Do not backfill missing T+5/T+10 by calling external market APIs inside diagnosis endpoints.
- Do not mark missing forward returns as AI score failure.
