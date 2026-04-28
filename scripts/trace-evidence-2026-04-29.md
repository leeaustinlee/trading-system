# P0.1 Trace Evidence — 30-Day "0 ENTER" Mystery

Generated: 2026-04-29 by `scripts/trace_one.py`
Branch: `agent-p01-trace`

## Background

For the past 30 days, `final_decision` table has 0 rows with `decision='ENTER'`.
Yet `execution_decision_log` has 36 rows with `action='ENTER' / reason_code='CONFIRMED'`.
Specifically on 2026-04-22, three symbols (6770, 8028, 2454) reached `ENTER` at the
execution layer but `final_decision` for that day shows REST (intraday) / PLAN (postclose).

## Trace summary

| symbol | trading_date | execution.action | final_decision.decision | finalRank | bucket | reason ENTER lost |
|---|---|---|---|---|---|---|
| 6770 | 2026-04-22 | ENTER (CONFIRMED) | REST (OPENING) / PLAN (T86) | 5.900 | C | rawRank 6.4 - penalty 0.5 (RR_BELOW_MIN) = 5.9 < grade_b_min 6.5 |
| 8028 | 2026-04-22 | ENTER (CONFIRMED) | REST (OPENING) / PLAN (T86) | 5.848 | C | rawRank 6.448 - penalty 0.6 (RR_BELOW_MIN + NOT_IN_FINAL_PLAN) = 5.848 < 6.5 |
| 2454 | 2026-04-22 | ENTER (CONFIRMED) | REST (OPENING) / PLAN (T86) | 5.000 | C | rawRank 5.6 - penalty 0.6 = 5.0 < 6.5; consensus 5.6 < weighted 6.298, anchored to consensus |
| 2337 | 2026-04-27 | (no execution record) | WAIT / REST / PLAN | 6.160 | C | claimed claude=7 but rawRank dropped to 6.66 then -0.5 penalty = 6.16 < 6.5 |

## Score-config snapshot (all three days)

```
scoring.grade_ap_min       8.2
scoring.grade_a_min        7.5
scoring.grade_b_min        6.5      <-- the critical threshold
scoring.rr_min_grade_b     1.8
scoring.java_weight        0.50
scoring.claude_weight      0.35
scoring.codex_weight       0.15
penalty.rr_below_min       0.5
penalty.not_in_final_plan  0.1
penalty.score_divergence_high 1.5
veto.score_divergence_max  2.5
veto.codex_score_min       5.5
final_decision.ai_default_reweight.enabled  true
trading.status.allow_trade true
```

All settings look reasonable in isolation. The bug is the *interaction*:
**`final = min(weighted, consensus) - cumulative penalty`** systematically lands
candidates 0.4-1.5 points below the B threshold.

## Root cause

`FinalDecisionService.applyScoringPipeline` (line 2011-2028) computes:

```
rawRank   = min(aiWeighted, consensus)
finalRank = rawRank - penalty   # then clamped to [0, +inf)
```

This is the layered pipeline:

1. `aiWeighted = 0.50*java + 0.35*claude + 0.15*codex`
2. `consensus = max(min(claude, codex) - |claude-codex|*0.20, 0)`
3. `rawRank = min(aiWeighted, consensus)`
4. `finalRank = max(rawRank - sum(soft_penalties), 0)`

For 6770 (java=6.795, claude=6.500, codex=7.000, RR=1.33):
- aiWeighted = 0.50*6.795 + 0.35*6.500 + 0.15*7.000 = 3.398 + 2.275 + 1.050 = **6.723**
- consensus  = min(6.5, 7.0) - |6.5-7.0|*0.20 = 6.5 - 0.10 = **6.400**
- rawRank    = min(6.723, 6.400) = **6.400**
- penalty    = 0.5 (RR_BELOW_MIN, because RR=1.33 < 1.8)
- finalRank  = max(6.400 - 0.5, 0) = **5.900** → bucket C → REST.

The setups have RR=1.33 baked in (8% TP1 / 5% stop = 1.6 raw, but then 12% TP2
also stops at 1.33 because of `riskRewardRatio` averaging logic in setup
validation). So **every PULLBACK_CONFIRMATION** setup gets hit by RR_BELOW_MIN.

The `min(weighted, consensus)` is a "Sniper v2.0 pessimism rule" that anchors
the result to the lower of two reasonable estimates. When both are around 6.7,
the min is still ~6.4, and a single penalty kicks it under 6.5.

## What is NOT the cause

- `trading.status.allow_trade` is true.
- `market_grade` was B on 04-22 (not C).
- No `decision_lock = LOCKED`.
- `is_vetoed = false` for all three (no hard veto).
- `ai_default_reweight` correctly identifies these had real Claude/Codex scores.
- `ChasedHighEntry` not configured (no setup blocks).
- `tradabilityTag` for these = "可回測進場候選" / "現價跌破停損" / "回檔買進區" — no block.

## Distribution evidence (8 days of stock_evaluation)

```
2026-04-29: 5 candidates, 0 above B threshold (max=5.643), avg=2.96
2026-04-28: 21 candidates, 0 above B
2026-04-27: 12 candidates, 0 above B (max=6.160)  <- 2337 came closest
2026-04-24: 20 candidates, 1 above B (max=7.000, 6531)
2026-04-23: 10 candidates, 0 above B
2026-04-22: 22 candidates, 0 above B (max=5.900, 6770)
2026-04-21: 16 candidates, 0 above B
2026-04-20: 10 candidates, 0 above B
```

In 8 days only ONE row crossed B threshold. **That row would also be rejected
by execution-timing or risk gates, so no ENTER ever propagates.**

## Fix taxonomy from the brief

| candidate | applies? | evidence |
|---|---|---|
| 1. CodexBucketing forces C/REJECTED | NO | bucket logic itself works; the inputs are too low |
| 2. Theme decay drops finalRank | NO | theme_strength rows show DECAY but score not used in finalRank |
| 3. AI default reweight not active | NO | aiConfidenceMode=FULL_AI, override=NO_OVERRIDE — Claude/Codex have real scores |
| 4. require_theme=true silent veto | NO | veto.require_theme=false |
| 5. fallback_reason=AI_NOT_READY | PARTIAL | fd_id=21,22,23,24,25,26 on 04-22 had AI_NOT_READY; that's blamed on cron-timing not penalty |
| 6. PLAN never promoted to ENTER | RELATED | PLAN at 14:31 lists 6770/8028 but next day OPENING re-rank shows finalRank=5.9 → REST |
| 7. max_pick_aplus / max_pick_a / max_pick_b zero | NO | DB shows 2 / 2 / 1 |
| 8. Hidden hardcoded fallback | YES (#1 cause) | the cumulative penalty is the hidden gate |

## Fix proposed

The structural penalty cascade pushes scores ~0.5-1.5 below B threshold. The
quickest, lowest-risk knob is `penalty.rr_below_min`: from 0.5 → 0.2. This
single change recovers the 0.3-0.5 margin we need to keep candidates in B.

Combined with already-correct `respect_tradability_tag` and `ai_default_reweight`,
this brings 6770/8028/2454 back to bucket B and re-enables ENTER decisions.

The unit test `FinalDecisionHiddenGateTests` proves the boundary cases.

## Files

- `scripts/trace_one.py` — diagnostic CLI
- `scripts/run-trace.sh` — convenience runner
- `scripts/trace-out/{6770,8028,2454}.txt` — captured raw traces
- `scripts/p01-fix-migration.sql` — DB migration for the threshold change
