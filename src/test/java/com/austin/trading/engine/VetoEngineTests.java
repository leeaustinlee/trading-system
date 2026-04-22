package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * VetoEngine tests — v2.6 MVP Refactor：SETUP 分支 hard + soft penalty 分流。
 */
class VetoEngineTests {

    private VetoEngine engine;
    private ScoreConfigService config;

    @BeforeEach
    void setUp() {
        config = mock(ScoreConfigService.class);
        // v1.0 defaults
        when(config.getString(eq("scoring.late_stop_market_grade"), any())).thenReturn("A");
        when(config.getDecimal(eq("scoring.rr_min_grade_a"), any())).thenReturn(new BigDecimal("2.2"));
        when(config.getDecimal(eq("scoring.rr_min_grade_b"), any())).thenReturn(new BigDecimal("2.5"));
        // v2.0 BC Sniper defaults
        when(config.getBoolean(eq("veto.require_theme"), anyBoolean())).thenReturn(true);
        when(config.getDecimal(eq("veto.codex_score_min"), any())).thenReturn(new BigDecimal("6.5"));
        when(config.getInt(eq("veto.theme_rank_max"), anyInt())).thenReturn(2);
        when(config.getDecimal(eq("veto.final_theme_score_min"), any())).thenReturn(new BigDecimal("7.5"));
        when(config.getDecimal(eq("veto.score_divergence_max"), any())).thenReturn(new BigDecimal("2.5"));
        // v2.6 MVP penalty defaults
        when(config.getDecimal(eq("penalty.rr_below_min"), any())).thenReturn(new BigDecimal("1.5"));
        when(config.getDecimal(eq("penalty.not_in_final_plan"), any())).thenReturn(new BigDecimal("0.5"));
        when(config.getDecimal(eq("penalty.high_val_weak_market"), any())).thenReturn(new BigDecimal("0.8"));
        when(config.getDecimal(eq("penalty.no_theme"), any())).thenReturn(new BigDecimal("1.0"));
        when(config.getDecimal(eq("penalty.codex_low"), any())).thenReturn(new BigDecimal("1.0"));
        when(config.getDecimal(eq("penalty.theme_not_top"), any())).thenReturn(new BigDecimal("0.8"));
        when(config.getDecimal(eq("penalty.theme_score_too_low"), any())).thenReturn(new BigDecimal("0.8"));
        when(config.getDecimal(eq("penalty.score_divergence_high"), any())).thenReturn(new BigDecimal("1.5"));
        when(config.getDecimal(eq("penalty.entry_too_extended"), any())).thenReturn(new BigDecimal("1.0"));
        engine = new VetoEngine(config);
    }

    // ── HARD VETO（真正風險紅線，觸發 vetoed=true）────────────────────────────

    @Test
    void marketGradeC_isHardVeto() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .marketGrade("C").build());
        assertTrue(result.vetoed());
        assertTrue(result.hardReasons().contains("MARKET_GRADE_C"));
        assertEquals(BigDecimal.ZERO, result.scoringPenalty(),
                "hard veto 觸發時不累 penalty");
    }

    @Test
    void decisionLocked_isHardVeto() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .decisionLock("LOCKED").build());
        assertTrue(result.vetoed());
        assertTrue(result.hardReasons().contains("DECISION_LOCKED"));
    }

    @Test
    void noStopLoss_isHardVeto() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .stopLossPrice(null).build());
        assertTrue(result.vetoed());
        assertTrue(result.hardReasons().contains("NO_STOP_LOSS"));
    }

    @Test
    void timeDecayLate_inNonAMarket_isHardVeto() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .marketGrade("B").timeDecayStage("LATE").build());
        assertTrue(result.vetoed());
        assertTrue(result.hardReasons().contains("TIME_DECAY_LATE"));
    }

    @Test
    void volumeSpikeWithoutBreakout_isHardVeto() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .volumeSpike(true).priceNotBreakHigh(true).build());
        assertTrue(result.vetoed());
        assertTrue(result.hardReasons().contains("VOLUME_SPIKE_NO_BREAKOUT"));
    }

    // ── SOFT PENALTY（扣分但 vetoed=false、仍進排序）──────────────────────────

    @Test
    void rrBelowMin_isSoftPenalty() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .riskRewardRatio(new BigDecimal("2.0")).build());
        assertFalse(result.vetoed(), "RR_BELOW_MIN 改 soft penalty，不再 hard veto");
        assertTrue(result.penaltyReasons().contains("PENALTY:RR_BELOW_MIN"));
        assertEquals(new BigDecimal("1.5"), result.scoringPenalty());
    }

    @Test
    void notInFinalPlan_isSoftPenalty() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .includeInFinalPlan(false).build());
        assertFalse(result.vetoed());
        assertTrue(result.penaltyReasons().contains("PENALTY:NOT_IN_FINAL_PLAN"));
        assertEquals(new BigDecimal("0.5"), result.scoringPenalty());
    }

    @Test
    void highValWeakMarket_isSoftPenalty() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .marketGrade("B").valuationMode("VALUE_HIGH").build());
        assertFalse(result.vetoed());
        assertTrue(result.penaltyReasons().contains("PENALTY:HIGH_VAL_WEAK_MARKET"));
    }

    @Test
    void noTheme_isSoftPenalty() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .hasTheme(false).build());
        assertFalse(result.vetoed(), "NO_THEME 改 soft penalty，不再 hard veto");
        assertTrue(result.penaltyReasons().contains("PENALTY:NO_THEME"));
        assertEquals(new BigDecimal("1.0"), result.scoringPenalty());
    }

    @Test
    void codexScoreLow_isSoftPenalty() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .codexScore(new BigDecimal("5.0")).build());
        assertFalse(result.vetoed());
        assertTrue(result.penaltyReasons().contains("PENALTY:CODEX_SCORE_LOW"));
    }

    @Test
    void themeNotInTop_isSoftPenalty() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .themeRank(3).build());
        assertFalse(result.vetoed());
        assertTrue(result.penaltyReasons().contains("PENALTY:THEME_NOT_IN_TOP"));
    }

    @Test
    void themeScoreTooLow_isSoftPenalty() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .finalThemeScore(new BigDecimal("6.0")).build());
        assertFalse(result.vetoed());
        assertTrue(result.penaltyReasons().contains("PENALTY:THEME_SCORE_TOO_LOW"));
    }

    @Test
    void scoreDivergenceHigh_isSoftPenalty() {
        // v2.8 P0.9: 改比 |claude - codex|（非 java-claude）
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .claudeScore(new BigDecimal("9.0"))
                .codexScore(new BigDecimal("6.0")).build());
        assertFalse(result.vetoed());
        assertTrue(result.penaltyReasons().contains("PENALTY:SCORE_DIVERGENCE_HIGH"));
    }

    @Test
    void javaClaudeDivergence_noLongerTriggersPenalty() {
        // v2.8 P0.9：|java - claude| 不再觸發 SCORE_DIVERGENCE_HIGH penalty（避免 double penalty）
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .javaScore(new BigDecimal("5.0"))
                .claudeScore(new BigDecimal("9.0"))
                .codexScore(new BigDecimal("8.5"))  // claude/codex 差 0.5 < 2.5
                .build());
        assertFalse(result.penaltyReasons().contains("PENALTY:SCORE_DIVERGENCE_HIGH"),
                "v2.8: java vs claude 分歧不再扣 SCORE_DIVERGENCE penalty");
    }

    @Test
    void entryTooExtended_isSoftPenalty() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .entryTooExtended(true).build());
        assertFalse(result.vetoed());
        assertTrue(result.penaltyReasons().contains("PENALTY:ENTRY_TOO_EXTENDED"));
    }

    // ── 組合行為 ──────────────────────────────────────────────────────────────

    @Test
    void cleanCandidate_noPenaltyNoVeto() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder().build());
        assertFalse(result.vetoed());
        assertEquals(BigDecimal.ZERO, result.scoringPenalty());
        assertTrue(result.penaltyReasons().isEmpty());
        assertTrue(result.hardReasons().isEmpty());
    }

    @Test
    void hardAndPenaltyTogether_hardBlocksAndSkipsPenalty() {
        // marketGrade=C (hard) + rrBelowMin (penalty) 同時觸發 → 只取 hard，penalty=0
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .marketGrade("C")
                .riskRewardRatio(new BigDecimal("1.0")).build());
        assertTrue(result.vetoed());
        assertTrue(result.hardReasons().contains("MARKET_GRADE_C"));
        assertEquals(BigDecimal.ZERO, result.scoringPenalty(),
                "hard veto 觸發後不再累 penalty（fail-fast）");
        assertTrue(result.penaltyReasons().isEmpty());
    }

    @Test
    void multiplePenaltiesAccumulate() {
        // rrBelowMin(1.5) + notInPlan(0.5) + noTheme(1.0) = 3.0
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .riskRewardRatio(new BigDecimal("1.5"))
                .includeInFinalPlan(false)
                .hasTheme(false).build());
        assertFalse(result.vetoed());
        assertEquals(new BigDecimal("3.0"), result.scoringPenalty());
        assertEquals(3, result.penaltyReasons().size());
    }

    @Test
    void legacyReasonsCombinesHardAndPenalty() {
        // 為向下相容（FinalDecisionService 寫入 stock_evaluation.veto_reasons 等），
        // reasons() 應反映當下所有被觸發的條件。
        VetoEngine.VetoResult vetoResult = engine.evaluate(baseInputBuilder()
                .hasTheme(false).build());
        assertFalse(vetoResult.vetoed());
        assertTrue(vetoResult.reasons().contains("PENALTY:NO_THEME"));
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private InputBuilder baseInputBuilder() {
        return new InputBuilder()
                .marketGrade("A")
                .decisionLock("NONE")
                .timeDecayStage("EARLY")
                .riskRewardRatio(new BigDecimal("2.5"))
                .includeInFinalPlan(true)
                .stopLossPrice(new BigDecimal("95.0"))
                .valuationMode("VALUE_GROWTH")
                .hasTheme(true)
                .themeRank(1)
                .finalThemeScore(new BigDecimal("8.0"))
                .codexScore(new BigDecimal("7.5"))
                .javaScore(new BigDecimal("8.5"))
                .claudeScore(new BigDecimal("8.0"))
                .volumeSpike(false)
                .priceNotBreakHigh(false)
                .entryTooExtended(false);
    }

    private static class InputBuilder {
        private String marketGrade = "A";
        private String decisionLock = "NONE";
        private String timeDecayStage = "EARLY";
        private BigDecimal riskRewardRatio = new BigDecimal("2.5");
        private Boolean includeInFinalPlan = true;
        private BigDecimal stopLossPrice = new BigDecimal("95.0");
        private String valuationMode = "VALUE_GROWTH";
        private Boolean hasTheme = true;
        private Integer themeRank = 1;
        private BigDecimal finalThemeScore = new BigDecimal("8.0");
        private BigDecimal codexScore = new BigDecimal("7.5");
        private BigDecimal javaScore = new BigDecimal("8.5");
        private BigDecimal claudeScore = new BigDecimal("8.0");
        private Boolean volumeSpike = false;
        private Boolean priceNotBreakHigh = false;
        private Boolean entryTooExtended = false;

        InputBuilder marketGrade(String v)           { this.marketGrade = v; return this; }
        InputBuilder decisionLock(String v)          { this.decisionLock = v; return this; }
        InputBuilder timeDecayStage(String v)        { this.timeDecayStage = v; return this; }
        InputBuilder riskRewardRatio(BigDecimal v)   { this.riskRewardRatio = v; return this; }
        InputBuilder includeInFinalPlan(Boolean v)   { this.includeInFinalPlan = v; return this; }
        InputBuilder stopLossPrice(BigDecimal v)     { this.stopLossPrice = v; return this; }
        InputBuilder valuationMode(String v)         { this.valuationMode = v; return this; }
        InputBuilder hasTheme(Boolean v)             { this.hasTheme = v; return this; }
        InputBuilder themeRank(Integer v)            { this.themeRank = v; return this; }
        InputBuilder finalThemeScore(BigDecimal v)   { this.finalThemeScore = v; return this; }
        InputBuilder codexScore(BigDecimal v)        { this.codexScore = v; return this; }
        InputBuilder javaScore(BigDecimal v)         { this.javaScore = v; return this; }
        InputBuilder claudeScore(BigDecimal v)       { this.claudeScore = v; return this; }
        InputBuilder volumeSpike(Boolean v)          { this.volumeSpike = v; return this; }
        InputBuilder priceNotBreakHigh(Boolean v)    { this.priceNotBreakHigh = v; return this; }
        InputBuilder entryTooExtended(Boolean v)     { this.entryTooExtended = v; return this; }

        VetoEngine.VetoInput build() {
            return new VetoEngine.VetoInput(
                    marketGrade, decisionLock, timeDecayStage,
                    riskRewardRatio, includeInFinalPlan, stopLossPrice, valuationMode,
                    hasTheme, themeRank, finalThemeScore,
                    codexScore, javaScore, claudeScore,
                    volumeSpike, priceNotBreakHigh, entryTooExtended
            );
        }
    }
}
