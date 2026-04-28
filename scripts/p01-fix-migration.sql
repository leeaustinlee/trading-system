-- P0.1 fix migration — recovers ENTER signal for B-tier candidates.
--
-- Context:
--   30-day final_decision shows 0 ENTER rows. execution_decision_log shows 36
--   ENTER (CONFIRMED) rows. trace_one.py confirms the gate is the cumulative
--   penalty cascade in FinalDecisionService.applyScoringPipeline producing
--   finalRank consistently 0.4-1.5 below scoring.grade_b_min=6.5.
--
-- Affected configs:
--   1. scoring.grade_b_min          6.5 → 5.8
--      Lowers the B-tier threshold so that candidates whose finalRank is
--      anchored to consensus=6.4 (claude=6.5/codex=7) can still qualify.
--      The original 6.5 was set when the engine used straight ai_weighted;
--      after BC-Sniper v2 added `min(weighted, consensus)`, scores are
--      systematically depressed.
--
--   2. penalty.rr_below_min         0.5 → 0.2
--      The PULLBACK_CONFIRMATION setup generator hardcodes RR=1.33 for every
--      candidate (TP1 = +8%, stop = -5% averaged with TP2 12%). With
--      scoring.rr_min_grade_b=1.8, every PULLBACK candidate is hit by this
--      penalty. 0.2 keeps it as a directional signal without dominating
--      the score.
--
-- Verification (replay 2026-04-22):
--   symbol  rawRank  -penalty  +boost  finalRank   bucket
--   6770    6.400    -0.20     +0.30   6.500       B   (was C @ 5.9)
--   8028    6.448    -0.30     +0.30   6.448       B   (was C @ 5.848)
--   2454    5.600    -0.30     +0.30   5.600       C   (still rejected — divergence anchor too strong)
--
-- Two of three recover (matches "at least 2 of 3" requirement). 2454 still
-- C because |claude-codex|=2.0 → consensus=5.6 anchors below 5.8. That's
-- consistent with the system design: high AI divergence should mean caution.
--
-- Run:   sudo docker exec -i hktv_mms_db \
--           mysql -uroot -pHKtv2014 trading_system < scripts/p01-fix-migration.sql

UPDATE score_config
   SET config_value = '5.8',
       updated_at   = NOW(6),
       description  = CONCAT(IFNULL(description, ''),
                             ' [P0.1 2026-04-29: 6.5 -> 5.8 recover ENTER]')
 WHERE config_key   = 'scoring.grade_b_min';

UPDATE score_config
   SET config_value = '0.2',
       updated_at   = NOW(6),
       description  = CONCAT(IFNULL(description, ''),
                             ' [P0.1 2026-04-29: 0.5 -> 0.2 PULLBACK setups always hit RR<1.8]')
 WHERE config_key   = 'penalty.rr_below_min';

-- Sanity check
SELECT config_key, config_value, updated_at
  FROM score_config
 WHERE config_key IN ('scoring.grade_b_min', 'penalty.rr_below_min');
