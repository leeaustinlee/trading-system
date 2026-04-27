# Paper Shadow Trading 實作審查 2026-04-28

Reviewer: independent senior code reviewer (cold review of `c8ae993..HEAD`).
Commits: `be75a2a` → `1669868` (A) → `a4dc75b` → `98707db` (B) → `68f93db` (C+D combined).
Touched: 22 files, +2102 / -23.

## A. Verdict

- **Overall: FIX_BEFORE_PUSH** — design is sound and tests pass, but two PnL/contract issues will silently
  poison the very dataset Austin wants for AI calibration. Both have one-line fixes.
- **Tests: 754 / 759 ✓ on `HEAD`.** The 5 failing tests
  (`ApiIntegrationTests.finalDecisionEvaluateShouldReturnEnter…`,
  `FullApiIntegrationTests.finalDecision_gradeC_shouldReturnRest`,
  3× `FinalDecisionExecutionRealityReplayIntegrationTests.opening_selectBuyNow_*`)
  were verified to **pre-exist on `c8ae993`** in a separate worktree — not regressions from this PR.
  Paper-trade tests (22 new): **all green** (`PaperTradeServiceTests` 7, `PaperTradeSnapshotServiceTests` 5,
  `PaperTradeStatsServiceTests` 10).
- **BLOCKERS: 0**
- **NEEDS_FIX: 4**

## B. Per-subagent scoring (5-axis grid)

| Subagent | Correctness | Side-effects | Test coverage | Risk | Reversibility | Score |
|---|---|---|---|---|---|---|
| **A — entry pipeline** | a-OK    | b-OK | c-OK   | d-NEEDS_FIX (PnL asymmetry, see C-1) | e-OK (DB flag `paper.auto_exit.enabled` + nullable cols) | **NEEDS_FIX** |
| **B — exit pipeline**  | a-NEEDS_FIX (REVERSE_SIGNAL keyword contract not wired) | b-OK | c-PARTIAL (no test for `runAutoExitCycle` / 7 triggers, only entity round-trip) | d-NEEDS_FIX (manual endpoint can race cron) | e-OK (`@ConditionalOnProperty` static + DB flag + cron property override) | **NEEDS_FIX** |
| **C — snapshot**       | a-OK    | b-OK | c-OK | d-NEEDS_FIX (idempotency check is read-then-write w/o unique constraint) | e-OK (own table, lazy backfill) | **NEEDS_FIX** |
| **D — stats**          | a-OK (Sharpe ✓, drawdown ✓, NaN guards ✓) | b-OK | c-OK | d-OK | e-OK (read-only, deriveGrade fallback for legacy rows) | **OK** |

## C. BLOCKERs

None.

## D. NEEDS_FIX

| # | file:line | problem | suggested fix |
|---|---|---|---|
| 1 | `PaperTradeService.java:621-623` | `pnlPct = pct(simulatedExit, entryPrice)` mixes **intended entry** (no slippage) with **simulated exit** (with slippage). The entry slippage is captured in `simulated_entry_price` but **never charged against PnL**, biasing reported returns upward by ~0.1 % on every closed trade. AI calibration on this dataset will overestimate strategy edge. | Use `simulatedEntry` consistently: `BigDecimal entryBasis = trade.getSimulatedEntryPrice() != null ? trade.getSimulatedEntryPrice() : trade.getEntryPrice(); BigDecimal pnlPct = pct(simulatedExit, entryBasis);`. Apply the same to the `pnlAmount` line right below. |
| 2 | `PaperTradeService.java:720-724` | `hasReverseSignal()` looks for the literal substrings `regime_change` / `strong_weakness` in `final_decision.summary`, but a repo-wide grep shows **no producer ever writes those keywords** into the summary column. The REVERSE_SIGNAL trigger is therefore **dead code** in production — a paper trade open during a true regime flip will not auto-exit. | Either (a) wire `MarketRegimeService` directly: read today's regime + previous regime, fire if `regime_change` flag toggled; or (b) at minimum, also accept `REGIME_CHANGE` / `STRONG_WEAKNESS` (uppercase) and document in `claudeMd` that the FinalDecisionService **must** emit one of these tokens when REST is driven by regime. Recommend (a). |
| 3 | `PaperTradeSnapshotService.java:90-99` | Idempotency relies on `findTop…orderByCapturedAtDesc` then `orElseGet(save)`. Under concurrent calls (no DB-level guard) two threads can both pass the check and both save → duplicate ENTRY snapshots. Not likely with the single-threaded scheduler, but `recordEntrySnapshot` is also called from the synchronous `onFinalDecisionPersisted` event handler which can fire from any thread. | Add `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"paper_trade_id","snapshot_type"}))` to `PaperTradeSnapshotEntity`, and wrap the insert in a try/catch on `DataIntegrityViolationException` returning the existing row. Hibernate auto-DDL will add the constraint. |
| 4 | `PaperExitController.java:32` + `PaperTradeService.runAutoExitCycle` | `POST /api/paper/exit-check` calls `job.run()` on the **request thread**, not via the scheduler. Spring's default `@Scheduled` pool is single-thread (so cron can't overlap itself), but the manual endpoint will happily race with an in-progress cron tick — both can read `OPEN`, both call `closeTradeFromAutoExit`, producing two `paper_trade_exit_log` FIRED rows for the same close. Not a money risk (paper trade), but corrupts audit. | Either (a) protect `runAutoExitCycle` with a `ReentrantLock.tryLock()` (return early if held); or (b) add `@Version` optimistic locking to `PaperTradeEntity.status` so the second save throws `OptimisticLockException` and is skipped. (a) is one line. |

## E. Strengths

- **Subagent A's `entry_payload_json` schema** is exactly the right shape for backtesting:
  candidate / entry / regime / themeExposure / capitalAlloc / scoringTrace / aiWeightOverride.
  The `schemaVersion: 1` field is the single most important addition — future schema changes
  won't silently break replay.
- **Subagent B priority order is correct** — STOP_LOSS (1) > TRAILING (2) > TP2 (3) > TP1 (4) >
  REVIEW_EXIT (5) > TIME_EXIT (6) > REVERSE_SIGNAL (7). I specifically checked for the inversion
  bug the prompt flagged (TP2 before STOP_LOSS): not present, the comparison logic is right
  (`<= stop` for stop-out, `>= tp2` for take-profit), and the loop returns the first matching
  trigger so a price below `stop_loss` AND above `tp2` (stale data) correctly fires STOP_LOSS not TP2.
- **Subagent C's idempotency-by-existing-row pattern** (return the existing snapshot instead of
  throwing) is graceful — pairs well with the lazy `backfillMissingExitSnapshots()` for late closes.
- **Subagent D's empty-list / zero-variance / NaN guards** are thorough. `computeMaxDrawdown`
  correctly handles strictly-increasing equity (returns 0, not negative DD), and the test
  `maxDrawdown_peakThenTrough_correctlyComputed` pins the +5% / -10% case at 10.0 % drawdown.
  Sharpe annualization `sqrt(252/avgHoldDays)` is the standard heuristic for non-overlapping
  trade returns and the `n<2` / `std<1e-9` short-circuits both return clean 0.
- **Constructor inflation in `PaperTradeService`** went from 7 → 11 args, but **all four new
  collaborators are `ObjectProvider<…>`** — this is exactly the right pattern for tests
  and keeps the bean wireable even when half the new tables haven't been created yet
  (graceful degradation through `getIfAvailable() == null`). The existing
  `PaperTradeServiceTests` was correctly updated with `stubProvider(null)` factories.
- **Feature flag hygiene**: `paper.auto_exit.enabled` is registered in `ScoreConfigService.DEFAULTS`
  (`true`, BOOLEAN) so toggling at runtime via the existing `/api/score-config` endpoint is a
  one-click rollback. Static `trading.scheduler.paper-trade-exit.enabled` provides
  belt-and-suspenders.
- **SW cache bump** (`tt-v4-…-twcolor` → `tt-v5-…-paper-trade`) is correctly tied to the
  mobile.html change so users won't see a stale page.

## F. AI Calibration 可行性

| Question | Answerable today? | Detail |
|---|---|---|
| 哪個 `entry_grade` 勝率最高? | **Yes** | `byGrade` map → `winRate`, `n`, `avgPnlPct`, `totalPnlPct`. Subagent A populates `entry_grade` at decision time so legacy rows fall back to `deriveGrade(finalRankScore)` thresholds. |
| 哪個 `theme_tag` 最常獲利? | **Yes** | `byTheme.totalPnlPct` (descending). One caveat — `theme_tag` is the snapshot of the symbol's primary theme **at entry**. If the symbol later switches theme that's not reflected. Acceptable for calibration. |
| 哪個 `exit_reason` 平均 PnL 最高? | **Yes** | `byExitReason.avgPnlPct`. With the 7 distinct triggers from Subagent B you'll see clean separation between TP1 / TP2 / STOP_LOSS / TRAILING / REVIEW_EXIT / TIME_EXIT / REVERSE_SIGNAL. |
| `final_rank_score` vs 後續報酬相關性? | **Yes, with one JOIN** | ENTRY snapshot's `payload_json` contains `finalRankScore`; EXIT snapshot's `payload_json` contains `pnlPct`. Both share `paper_trade_id`. SQL: `SELECT JSON_EXTRACT(e.payload_json,'$.finalRankScore'), JSON_EXTRACT(x.payload_json,'$.pnlPct') FROM paper_trade_snapshot e JOIN paper_trade_snapshot x ON e.paper_trade_id=x.paper_trade_id WHERE e.snapshot_type='ENTRY' AND x.snapshot_type='EXIT'`. Confirmed possible. **However** — if **D-1** isn't fixed, the `pnlPct` is biased and the regression slope will be wrong. |
| 反向回推「想到 X 筆 ENTER 需要 grade_b_min 多少」? | **Partial — need extra table** | Today: `byGrade` gives realised counts per grade, but **the candidate-distribution histogram (how many candidates were *evaluated* at each score bucket on each day) is not recorded**. Only ENTERed rows survive; the WAIT/REST tail is invisible. To make this answerable Austin needs a new `score_bucket_daily` table populated by `FinalDecisionService` (one row per `(date, grade, count_evaluated, count_entered)`). Suggested as a P1 follow-up — out of scope for this PR. |

**Net verdict on calibration value of this PR**: ~80 %. After the four NEEDS_FIX items are
addressed, the dataset is **clean enough to drive grade-threshold and weight-tuning decisions**.
The score-bucket histogram is the missing piece to do counterfactual analysis ("if I lowered
A_NORMAL threshold by 0.5, how many extra trades and at what hit-rate?") and should be the
next ticket.
