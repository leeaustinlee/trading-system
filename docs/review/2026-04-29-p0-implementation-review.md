# P0 落地審查 2026-04-29

Independent reviewer pass over `74ade1e..d5799da` (3 subagents merged + main session
DEFAULTS consolidation). Reviewed cold from git diff — no prior conversation context.

## A. Verdict

- **Overall:** `FIX_BEFORE_PUSH` (small surface — 2 NEEDS_FIX, 0 BLOCKERS)
- **Tests:** 101/101 (claimed; reviewer did not run — see C/D for the gaps in the
  *coverage* itself, not in pass/fail)
- **BLOCKERS:** 0
- **NEEDS_FIX:** 2 (one each on B and C; also one doc-hygiene item on A)

The change is low-blast-radius despite touching three layers, because every new
behaviour is gated by a feature flag that is reversible via SQL. Subagent A's
DB-only migration is the most aggressive piece, but the trace evidence and the
boundary test (`5.79 < 5.8`) demonstrate the threshold drop is precisely
calibrated to the hidden-gate cascade; it does not unilaterally widen the
candidate funnel.

## B. Per-subagent scoring

| axis | A (P0.1 trace + threshold) | B (P0.2 regime real downgrade) | C (P0.3 EXIT auto-close shadow) |
|---|---|---|---|
| (a) Correctness          | OK   | OK         | OK |
| (b) Side effects         | OK   | NEEDS_FIX  | NEEDS_FIX |
| (c) Test coverage        | OK   | OK         | NEEDS_FIX |
| (d) Risk                 | OK   | NEEDS_FIX  | OK (mitigated by paper_only) |
| (e) Reversibility        | OK   | OK         | OK |

Brief justification per cell:

- **A.a** Penalty math validated against trace rows (6770: 6.4 - 0.5 = 5.9 → C;
  6.4 - 0.2 + 0.3 boost = 6.5 → B). `FinalDecisionEngine` reads
  `final_rank_score` post-pipeline so the engine-level test is a faithful
  replay even though the upstream `applyScoringPipeline` isn't re-exercised.
- **A.b** Threshold drop targets the empirically observed band (5.6-6.4). 30-day
  evidence: only 1/8 days had any candidate above the *old* 6.5; under 5.8 the
  funnel widens to ~3-5 ENTER signals/day historically (extrapolated from
  `execution_decision_log` 36 ENTERs / 30 days). PriceGate, ChasedHigh, AI
  veto, theme veto remain as downstream filters. **No** sign of "5.5-5.8
  rejected for a good reason" in the trace files — every C-bucket row in
  6770/8028/2454 had `is_vetoed=false` and a single `RR_BELOW_MIN` hit.
- **A.c** Three engine-level tests cover pre-fix block, post-fix recover (two
  candidates), and a 5.79 boundary. Missing: coverage that the *upstream
  pipeline* (consensus/penalty math in `FinalDecisionService`) actually
  produces ~5.9 from java=6.795/claude=6.5/codex=7 — but that's an existing
  gap and the trace evidence covers it observationally.
- **A.d** No path for "weak candidate ENTER bypass". B-bucket caps `max_pick_b=1`
  at 0.5x position factor, so even with the looser threshold, only 1 trial-size
  ENTER per day from the lowest tier.
- **A.e** SQL-only migration; rollback is a single `UPDATE score_config SET
  config_value='6.5' WHERE config_key='scoring.grade_b_min'`. PostConstruct
  seeding is idempotent (`init()` line 267 only inserts if missing) so the
  DB-side 5.8 survives restarts.

- **B.a** Trigger math: CONSEC_DOWN counts strict day-over-day decreases in a
  sliding window (`maxStreak >= requiredDown`); SEMI_WEAK uses
  `(today - old) / old * 100` cumulative pct vs `-3.0` threshold;
  TAIEX_BELOW_60MA precomputed-MA-or-fall-through; DAILY_LOSS_CAP sums paper +
  realised + best-effort unrealised. All four gracefully no-op on missing data
  via try-catch + `Optional.empty()`. Math is conservative and correct.
- **B.b** `NoopMarketIndexProvider` is the active production binding, so the 3
  index-driven triggers are **silent no-ops in prod**. Only DAILY_LOSS_CAP can
  fire today, and it requires real paper-trade losses ≥ 5,000 NTD on the
  current trading day. This is the dominant risk: P0.2 advertises "real
  C-grade downgrade" but most of that is theoretical until a real
  `MarketIndexProvider` lands. NEEDS_FIX (not BLOCKER): pass-through on
  missing data is the correct fail-safe; the issue is a documentation /
  follow-up gap, not a bug.
- **B.c** 7 tests cover each trigger isolated, multi-trigger accumulation, flag
  off, no-trigger pass-through. Boundary cases (exactly 60 closes, exactly
  -3.0% return, ZERO old close guard) are implicitly covered by the
  `signum() <= 0` and `closes.size() < maLen` guards. Good coverage.
- **B.d** Default thresholds (3-day streak in 5-day window; 60-MA; -3% / 5
  days; -5,000 NTD) are conservative. Each is independently configurable.
  CONSEC_DOWN with `requiredDown=3` could fire on normal pullbacks during a
  3-day correction in a rising tape, which would over-protect. Mitigation: the
  feature flag.
- **B.e** Single flag `market_regime.real_downgrade.enabled=false` reverts.
  Per-trigger thresholds are also tunable via score_config — granular rollback
  available.

- **C.a** `maybeAutoClosePosition` ordering is correct: called before
  `pos.setReviewStatus(...)` so transition gate reads the *previous* persisted
  status. `mirrorToPaperTrade` finds OPEN paper_trade by symbol; if multiple
  match, "earliest first" (sorted entry_date asc + id asc) — defensible.
  `paper_only` flag gating `closeRealPosition` is clean.
- **C.b** Two side-effect concerns:
  1. **First-review case** (`prev=null`): the gate `"EXIT".equalsIgnoreCase(null)`
     is `false`, so an EXIT decision on first-ever review *does* fire auto-close
     immediately. In paper_only mode this is fine; once `paper_only=false`
     this becomes a real-position close on potentially noisy data. NEEDS_FIX
     (defer until shadow window ends).
  2. **Multi-position-same-symbol** (#6 from prompt): "earliest" match is a
     reasonable heuristic but mismatches if the user has two real positions on
     the same symbol from different days — only one paper_trade gets closed.
     Acceptable for shadow but documentation should call this out.
- **C.c** 6 tests cover the happy path (paper-only & paper+real), dedupe (prev
  =EXIT), kill-switch, no-matching-paper. **Missing**:
  - No test for `prev=null` (first review). Given the feature flag fail-safe
    (paper_only=true default), this is NEEDS_FIX rather than BLOCKER.
  - No test for the multi-position-same-symbol case.
- **C.d** Risk is well-bounded by `paper_only=true` default. The 5-day shadow
  SOP in `docs/runbook/2026-04-29-position-auto-close-rollout.md` is detailed
  and includes a KPI gate before flipping.
- **C.e** Two flags: kill-switch (`auto_close.enabled`) and shadow toggle
  (`auto_close.paper_only`). Both reversible via SQL. Excellent.

## C. BLOCKERs

None.

## D. NEEDS_FIX

| file:line | problem | suggested fix |
|---|---|---|
| `service/regime/NoopMarketIndexProvider.java:33-55` | NOOP provider is the only `MarketIndexProvider` impl in production today. Three of four downgrade triggers (CONSEC_DOWN / TAIEX_BELOW_60MA / SEMI_WEAK) silently no-op forever until a real provider lands. The PR copy ("4-trigger real downgrade") implies more coverage than is actually live. | Two-step: (1) keep code as-is — the fail-safe is correct. (2) **before** marking P0.2 done, add a follow-up task to wire a real provider (e.g. read TAIEX from `market_snapshot.payload_json.index_value` history, or new `taiex_daily` table). Also add a `WARN`-level log on every `applyIfNeeded` invocation when `getTaiexCloses(today, n)` returns empty, so ops can see "P0.2 effectively dormant today". Currently only logged once at startup. |
| `service/PositionReviewService.java:388-392` | Transition gate `prev != EXIT` evaluates to TRUE when `prev=null` (first review). In `paper_only=true` shadow mode this is harmless, but once flipped to `paper_only=false` (5 days from now), an EXIT decision on the very first review of a fresh position fires real auto-close immediately — no "wait one more review" guardrail. Race scenario: stale data on first review reads `EXIT`, next review would have read `HOLD`, but the position is already gone. | Tighten to `prev != null && prev != EXIT && prev != /*virgin sentinel*/`, OR require `prev` to be a non-null actual prior status (e.g. `HOLD`/`WEAKEN`/`TRAIL_UP`). Add `transitionToExit_firstReview_prevNull_doesNotAutoCloseInRealMode` test before flipping `paper_only=false`. **Block the 5-day flip on this fix landing.** |
| `service/ScoreConfigService.java:62` | `DEFAULTS` map still says `scoring.grade_b_min` = `6.5` while the production DB row is now `5.8` (via `scripts/p01-fix-migration.sql`). PostConstruct only seeds when row missing, so behaviour is correct, but anyone reading the codebase will see 6.5 and be confused — and on a fresh DB / new dev environment, the seed will install 6.5 and the system will silently revert to the old hidden-gate behaviour with zero warning. Same applies to `penalty.rr_below_min` (DEFAULTS=0.5, prod=0.2) | Update both DEFAULTS values to match the migration target (`6.5`→`5.8`, `0.5`→`0.2`) **and** append a comment pointing at `p01-fix-migration.sql`. This keeps fresh-DB bootstraps consistent with the production gate calibration. |

## E. Strengths

1. **Trace tooling is the real win.** `scripts/trace_one.py` is general-purpose
   and will pay back on every future "why did X get rejected" question. The
   verdict reasoner (`derive_verdict`) is structured enough to be re-used as a
   regression check (e.g. "if `RR_BELOW_MIN` flag still appears in N% of
   rejected rows post-fix, fail CI"). Adding a JSON output mode means tests
   could assert against trace structure.
2. **Subagent A documented the failure mode publicly** — `trace-evidence-2026-
   04-29.md` shows the actual SQL math (java=6.795, claude=6.5, codex=7 →
   weighted=6.723, consensus=6.4, min=6.4, finalRank=5.9). This is the kind of
   evidence that makes the threshold drop defensible rather than an ad-hoc
   knob-twist.
3. **Subagent B's fail-safe contract is articulated correctly.** The
   `MarketIndexProvider` interface explicitly forbids fabricated data; the
   javadoc says "Missing data → empty list / Optional.empty()". This is the
   safer default given that the alternative (synthetic prices) would force
   regime triggers to fire on noise.
4. **Subagent C's 5-day shadow + paper_only-first rollout** is exactly the
   right pattern for a feature that touches real positions. The runbook spells
   out the KPI gate (winrate ≥ 50% on sample ≥ 5) before flipping. This is
   institutional-grade rollout discipline.
5. **All three subagents added focused tests** — 16 new tests total
   (FinalDecisionHiddenGateTests×3, MarketRegimeRealDowngradeTests×7,
   PositionReviewExitAutoCloseTests×6). Each test maps to a concrete branch
   condition or boundary case. No "test the test" fluff.
6. **Feature flags everywhere.** Every behavioural change can be reverted via
   `UPDATE score_config` without a code commit. This is the right architecture
   for a system that's still calibrating.

## F. Strategy-level concerns (≤ 3)

### F1. Subagent A's threshold drop — how aggressive is 6.5 → 5.8?

Numerical answer: the gap covers 0.7 score units. 30-day evidence shows the
maximum finalRank in 8 sampled days was 7.0 (one row, 04-24, 6531) and the
modal max-of-day was in the 5.9-6.4 band. Under the new threshold:

- **Old 6.5:** in 8 days, 1 row crossed the gate.
- **New 5.8:** in 8 days, ~10-15 rows would cross (rough count from
  `trace-evidence-2026-04-29.md` distribution).

That's NOT "20+ ENTER per day" — most of those would still be rejected by:
- **AI weighted reweight** when claude/codex are default 3.0 (the
  `aiConfidenceMode=FULL_AI` rows in 6770/8028/2454 had real research, so
  weren't reweighted)
- **PriceGate / ChasedHigh** (orthogonal to score)
- **`max_pick_b=1`** (hard cap on B-tier picks)
- **B-bucket position factor 0.5x** (smaller positions even on bucket pass)

Net expected change: from 0 ENTERs/day to ~1-2 ENTERs/day, all at
试单/B_TRIAL size. That's *exactly* what Austin's CLAUDE.md asks for ("3-5 万
單檔，最多 3 檔"). Not over-trading.

**One subtle concern:** 6770 had `theme_strength.tradable=False` and
`theme_stage=DECAY` in the trace. Under the new threshold it would have
ENTERed despite the theme being explicitly marked non-tradable. Check whether
`VetoEngine` already gates on `tradable=false` — if not, that's a separate
gap, not introduced by P0.1 but exposed by it.

### F2. Subagent B's NOOP provider — false sense of safety?

Borderline. The PR ships a 4-trigger downgrade evaluator but ships a NOOP
provider as the only production binding for 3 of those 4 triggers. The risk
isn't *correctness* (the fail-safe is correct: no data → no fire → no
downgrade) — the risk is *operator perception*: dashboards / runbooks /
LINE messages will say "P0.2 active, 4 hard triggers protecting downside" when
in reality only DAILY_LOSS_CAP can ever fire today.

Recommendation:

1. Add a startup `WARN` log: `"[P0.2] MarketIndexProvider is NOOP — 3 of 4
   downgrade triggers are inactive. CONSEC_DOWN/TAIEX_BELOW_60MA/SEMI_WEAK
   will not fire until a real provider is registered."` (currently logged at
   `INFO` once per startup, easy to miss).
2. File a follow-up to wire a real provider against `market_snapshot` history
   or TWSE crawl. Don't claim P0.2 "done" in changelog notes until that lands.

This is NEEDS_FIX rather than BLOCKER because the system is at worst as safe
as it was pre-P0.2 (engine still produces A/B), and DAILY_LOSS_CAP — the most
behaviourally important trigger for "today went bad" — does work end-to-end.

### F3. Subagent C's first-review race

Real but mitigated. The transition gate `prev != EXIT` admits `prev=null` →
auto-close fires on the very first review of a brand-new position. In
`paper_only=true` mode (next 5 trading days), only paper_trade is touched, so
no harm. But if Austin flips `paper_only=false` on 2026-05-05 without
tightening the gate, the first review of a noisy intraday quote could trigger
real auto-close on day 1 of a position.

The fix is two lines:

```java
String prevStatus = pos.getReviewStatus();
if (prevStatus == null || "EXIT".equalsIgnoreCase(prevStatus)) {
    log.debug("[PositionReview] auto-close skipped (prev=null|EXIT) symbol={}", pos.getSymbol());
    return;
}
```

Plus a test: `transitionToExit_firstReview_prevNull_doesNotAutoClose`.

Recommend: **gate the 2026-05-05 paper_only=false flip on this fix landing**.
Until then, the shadow window is genuinely shadow, and any first-review false
positive is harmless.

---

## Author note

This review is structurally sound — the three subagents executed independent
slices well and the main session's DEFAULTS consolidation was clean. The
issues called out are all "tighten before flipping the next switch" rather
than "this commit is broken". Approve for merge with the three NEEDS_FIX items
tracked as immediate follow-ups (specifically: D-row 3 doc-hygiene fix can ship
in the same merge window; D-row 1 needs a real `MarketIndexProvider`
implementation; D-row 2 must land before `paper_only=false` flip).

— Reviewer: senior-code-review pass, 2026-04-29
