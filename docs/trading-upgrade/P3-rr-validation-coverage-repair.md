# P3 RR Validation Coverage Repair

## API

Manual repair endpoint:

```bash
POST /api/backtest/diagnosis/rr-shadow-validation/repair-coverage?days=60
```

The endpoint:

1. Reads the existing `rr_shadow_validation` rows in the `days` window.
2. Resolves the required symbols and market-data window from `oldestEntryDate - 5` to `newestEntryDate + 20`, capped at today.
3. Backfills `market_index_daily` for `t00` and RR validation symbols.
4. Runs `CandidateForwardReturnBackfillService.backfillReturns(days)`.
5. Runs `RrShadowValidationService.backfill(days)` again so RR validation return fields are rewritten from available data.
6. Returns `before` and `after` summaries, symbols, upsert counts, data gaps, and coverage delta.

## Safety

This is shadow-only / diagnosis-only.

The repair endpoint writes only coverage inputs and diagnostics:

- `market_index_daily`
- `candidate_forward_tracking`
- `rr_shadow_validation`

It does not create auto-orders, does not change production BUY decisions, and does not trigger real-position SELL rules.

## Coverage Calculation

`blockedReturnCoveragePct` is calculated over RR shadow `FAIL` rows only.

A blocked row is covered when at least one of `T1`, `T3`, `T5`, or `T10` return is present. It does not require all four horizons. This allows partial horizons to improve validation as soon as short-horizon data exists.

`RrShadowValidationService.backfill(days)` now fills missing RR validation returns directly from `market_index_daily` using the paper trade `entryDate`, `symbol`, and `entryPrice`. It does not require a matching `candidate_forward_tracking` row.

Benchmark gaps are reported separately through `coverageGaps.missingBenchmark`. Missing benchmark data does not block individual stock return fields from being filled.

## Data Gap Fields

Summary and repair responses include:

- `coverageGaps.missingSymbols`
- `coverageGaps.missingBenchmark`
- `coverageGaps.missingHorizons`
- `coverageGaps.oldestEntryDate`
- `coverageGaps.newestEntryDate`

Forward-return gaps are treated as market-data coverage gaps, not AI score failures.

## Next Step

Consider moving RR from shadow validation to decision advisory only after:

- blocked-row coverage is consistently high enough for the recent live window;
- would-block rows have enough T1/T3/T5/T10 observations to compare avoided losers vs missed winners;
- root-cause buckets are stable across multiple windows;
- the advisory remains non-executing and does not place BUY/SELL orders automatically.
