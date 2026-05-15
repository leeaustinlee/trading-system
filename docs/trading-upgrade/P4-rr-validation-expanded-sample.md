# P4 RR Validation Expanded Sample

## Why 12 rows is not enough

Current live RR shadow validation has only 12 rows, all from `paper_trade`.
That is useful for plumbing and coverage checks, but not enough to judge whether
RR shadow rules should influence live decisions. A small paper-only sample can
overfit to a few final ENTER cases and misses WATCH/WAIT/REJECT observations
that are important for estimating avoided losers and missed winners.

P4 expands the diagnosis sample with `candidate_forward_tracking` rows. These
rows remain diagnosis-only and are stored as `source_type=FORWARD_CANDIDATE`.

## Forward candidate proxy RR limits

Forward candidates do not always have a real trade plan with stop and target
prices. P4 therefore creates a conservative proxy plan from
`entryPriceAtDecision`:

- Momentum / Continuation / Breakout: stop `entry * 0.94`, target1 `entry * 1.08`, target2 `entry * 1.12`
- Pullback / Setup: stop `entry * 0.96`, target1 `entry * 1.06`, target2 `entry * 1.10`
- Default: stop `entry * 0.95`, target1 `entry * 1.07`, target2 `entry * 1.10`

Every proxy row writes:

`PROXY_RR_PLAN_FROM_FORWARD_CANDIDATE; SHADOW_ONLY`

This note is required so the row is not confused with an actual trade plan.

## Shadow-only safety

P4 writes only `rr_shadow_validation` diagnostics. It does not create orders,
does not change the production BUY path, and does not let any new rule trigger
real BUY/SELL decisions. The expanded endpoint is:

`POST /api/backtest/diagnosis/rr-shadow-validation/backfill-expanded?days=180`

The existing paper-trade endpoint remains unchanged:

`POST /api/backtest/diagnosis/rr-shadow-validation/backfill?days=60`

## Promotion readiness

The summary now includes grouped diagnostics and `promotionReadiness`.

Minimum thresholds:

- `minSampleThreshold = 50`
- `minCoveragePct = 80`
- `maxMissedWinnerPct = 20`

Statuses:

- `INSUFFICIENT_SAMPLE`
- `NEED_MORE_FORWARD_COVERAGE`
- `SHADOW_OK_BUT_NOT_PRODUCTION`
- `CANDIDATE_FOR_SOFT_ADVISORY`

Even when thresholds pass, P4 can only become a soft advisory candidate. It must
not be promoted directly to a production gate.

## Next steps

Collect at least 50 observations, then preferably 100+ observations, across
paper trades and forward candidates. Recheck source, strategy, final decision,
theme, and root-cause buckets before considering any soft advisory use. A
production gate requires a separate safety review and implementation path.
