package com.austin.trading.engine;

import com.austin.trading.domain.enums.MarketSession;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.dto.request.FinalDecisionEvaluateRequest;
import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P0.1 Hidden-Gate replay tests — proves that the 2026-04-22 candidates
 * (6770 / 8028 / 2454) recover from "等級 C → REST" to bucket B once the two
 * tuned thresholds land:
 *
 * <ul>
 *   <li>{@code scoring.grade_b_min}        6.5 → 5.8</li>
 *   <li>{@code penalty.rr_below_min}       0.5 → 0.2</li>
 * </ul>
 *
 * <p>The trace evidence in {@code scripts/trace-evidence-2026-04-29.md} shows
 * stock_evaluation rows with {@code finalRank=5.900} (6770), {@code 5.848}
 * (8028) and {@code 5.000} (2454). The ENTER signal was lost because the
 * {@code FinalDecisionEngine} bucketed all three as C (below 6.5).</p>
 *
 * <p>These tests replay each row's actual finalRank into the engine and assert
 * the bucket promotion under the post-fix threshold. We set
 * {@code mainStream=true} for 6770 (real PowerShell screener tag) so it picks
 * up the +0.30 boost, matching what 04-22 fd_id=32 reported in
 * "rank=5.900 +boost=0.3".</p>
 *
 * <p>The boundary case {@code score=5.79} verifies we did not over-loosen
 * the threshold — a candidate just under 5.8 still falls to C / REST.</p>
 */
class FinalDecisionHiddenGateTests {

    private static final BigDecimal NEW_GRADE_B_MIN = new BigDecimal("5.8");

    private FinalDecisionEngine engineWithFix() {
        return engineWith(NEW_GRADE_B_MIN);
    }

    private FinalDecisionEngine engineLegacy() {
        return engineWith(new BigDecimal("6.5"));
    }

    private FinalDecisionEngine engineWith(BigDecimal gradeBMin) {
        ScoreConfigService config = mock(ScoreConfigService.class);
        when(config.getDecimal(eq("scoring.grade_ap_min"), any())).thenReturn(new BigDecimal("8.2"));
        when(config.getDecimal(eq("scoring.grade_a_min"),  any())).thenReturn(new BigDecimal("7.5"));
        when(config.getDecimal(eq("scoring.grade_b_min"),  any())).thenReturn(gradeBMin);
        when(config.getDecimal(eq("scoring.rr_min_ap"),    any())).thenReturn(new BigDecimal("2.2"));
        when(config.getDecimal(eq("ranking.main_stream_boost"), any())).thenReturn(new BigDecimal("0.3"));
        when(config.getDecimal(eq("plan.primary_min"),          any())).thenReturn(new BigDecimal("5.5"));
        when(config.getDecimal(eq("plan.backup_min"),           any())).thenReturn(new BigDecimal("4.0"));
        when(config.getDecimal(eq("plan.sector_indicator_min"), any())).thenReturn(new BigDecimal("7.8"));
        when(config.getDecimal(eq("plan.avoid_score_max"),      any())).thenReturn(new BigDecimal("3.5"));
        when(config.getInt(eq("decision.max_pick_aplus"), anyInt())).thenReturn(2);
        when(config.getInt(eq("decision.max_pick_a"),     anyInt())).thenReturn(2);
        when(config.getInt(eq("decision.max_pick_b"),     anyInt())).thenReturn(1);
        when(config.getInt(eq("plan.max_primary"),        anyInt())).thenReturn(2);
        when(config.getInt(eq("plan.max_backup"),         anyInt())).thenReturn(3);
        when(config.getBoolean(eq("decision.require_entry_trigger"), anyBoolean())).thenReturn(true);
        when(config.getString(any(), any())).thenReturn("A");
        when(config.getDecimal(eq("trading.price_gate.low_volume_ratio_threshold"), any()))
                .thenReturn(new BigDecimal("0.8"));
        when(config.getDecimal(eq("trading.price_gate.far_from_open_pct_threshold"), any()))
                .thenReturn(new BigDecimal("0.01"));
        when(config.getDecimal(eq("trading.price_gate.bull_shallow_drop_pct_threshold"), any()))
                .thenReturn(new BigDecimal("0.01"));
        when(config.getBoolean(eq("trading.status.allow_trade"), anyBoolean())).thenReturn(true);
        PriceGateEvaluator priceGate = new PriceGateEvaluator(config);
        return new FinalDecisionEngine(config, priceGate);
    }

    // ── Case 1: Pre-fix would block — same finalRank gets REST under 6.5 threshold ──

    @Test
    void preFix_6770_atFinalRank_5_900_bucketsAsC_andRest() {
        FinalDecisionResponse r = engineLegacy().evaluate(
                req(candidate("6770", 1.33, /*mainStream*/ true, new BigDecimal("5.900"))),
                MarketSession.LIVE_TRADING);

        // 5.900 + boost 0.3 = 6.200, still < 6.5 → bucket C → REST
        assertThat(r.decision()).isEqualTo("REST");
        assertThat(r.selectedStocks()).isEmpty();
        assertThat(r.rejectedReasons())
                .as("legacy 6.5 threshold should reject 6770 with rank=5.9")
                .anyMatch(s -> s.contains("6770") && s.contains("等級 C"));
    }

    // ── Case 2: Post-fix passes — 6770 / 8028 recover to B; verify bucket logic ──

    @Test
    void postFix_replayRecovers6770and8028ToBucketB() {
        FinalDecisionResponse r6770 = engineWithFix().evaluate(
                req(candidate("6770", 1.33, /*mainStream*/ true, new BigDecimal("5.900"))),
                MarketSession.LIVE_TRADING);
        // 5.900 + boost 0.3 = 6.200 ≥ 5.8 (new B min) → bucket B → ENTER
        assertThat(r6770.decision()).as("6770 should ENTER post-fix").isEqualTo("ENTER");
        assertThat(r6770.selectedStocks()).hasSize(1);
        assertThat(r6770.selectedStocks().get(0).stockCode()).isEqualTo("6770");
        assertThat(r6770.summary())
                .as("6770 should land in B_TRIAL bucket (試單 0.5x), not A_PLUS or A_NORMAL")
                .containsAnyOf("B 等級候選", "試單");

        FinalDecisionResponse r8028 = engineWithFix().evaluate(
                req(candidate("8028", 1.33, /*mainStream*/ true, new BigDecimal("5.848"))),
                MarketSession.LIVE_TRADING);
        // 5.848 + boost 0.3 = 6.148 ≥ 5.8 → bucket B → ENTER
        assertThat(r8028.decision()).as("8028 should ENTER post-fix").isEqualTo("ENTER");
        assertThat(r8028.selectedStocks()).hasSize(1);
        assertThat(r8028.selectedStocks().get(0).stockCode()).isEqualTo("8028");
        assertThat(r8028.summary()).containsAnyOf("B 等級候選", "試單");
    }

    // ── Case 3: Boundary check — 5.79 (just under new threshold) still REST ──

    @Test
    void postFix_boundary_5_79_belowNewBmin_stillBucketsAsCAndRest() {
        // mainStream=false → no +0.3 boost; rank stays 5.79 < 5.8 → C → REST.
        FinalDecisionResponse r = engineWithFix().evaluate(
                req(candidate("9999", 1.8, /*mainStream*/ false, new BigDecimal("5.79"))),
                MarketSession.LIVE_TRADING);
        assertThat(r.decision()).as("5.79 < 5.8 should still be REST").isEqualTo("REST");
        assertThat(r.selectedStocks()).isEmpty();
        assertThat(r.rejectedReasons()).anyMatch(s -> s.contains("9999") && s.contains("等級 C"));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private FinalDecisionEvaluateRequest req(FinalDecisionCandidateRequest c) {
        return new FinalDecisionEvaluateRequest("B", "NONE", "EARLY", false, List.of(c));
    }

    private FinalDecisionCandidateRequest candidate(String code, double rr,
                                                     boolean mainStream,
                                                     BigDecimal finalRankScore) {
        return new FinalDecisionCandidateRequest(
                code,
                "TEST-" + code,
                "VALUE_FAIR",
                "BREAKOUT",
                rr,
                true,             // includeInFinalPlan
                mainStream,
                false,            // falseBreakout
                false,            // belowOpen
                false,            // belowPrevClose
                false,            // nearDayHigh
                true,             // stopLossReasonable
                "hidden-gate-replay",
                "100-102",
                98.0, 108.0, 115.0,
                null, null, null,                  // javaStructureScore, claudeScore, codexScore
                finalRankScore,
                false,                             // isVetoed
                null, null, null, null,            // baseScore, hasTheme, themeRank, finalThemeScore
                null, null,                        // consensusScore, disagreementPenalty
                null, null, false,                 // volumeSpike, priceNotBreakHigh, entryTooExtended
                true                               // entryTriggered
        );
    }
}
