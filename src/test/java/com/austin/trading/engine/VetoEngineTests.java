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
        engine = new VetoEngine(config);
    }

    // ── v1.0 規則 ────────────────────────────────────────────────────────────

    @Test
    void marketGradeCVeto() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .marketGrade("C").build());
        assertTrue(result.vetoed());
        assertTrue(result.reasons().contains("MARKET_GRADE_C"));
    }

    @Test
    void decisionLockedVeto() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .decisionLock("LOCKED").build());
        assertTrue(result.vetoed());
        assertTrue(result.reasons().contains("DECISION_LOCKED"));
    }

    @Test
    void rrBelowMinForGradeA() {
        // Grade A，rr < 2.2 → veto
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .riskRewardRatio(new BigDecimal("2.0")).build());
        assertTrue(result.vetoed());
        assertTrue(result.reasons().contains("RR_BELOW_MIN"));
    }

    @Test
    void rrAboveMinShouldPass() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder().build());
        assertFalse(result.reasons().contains("RR_BELOW_MIN"));
    }

    // ── v2.0 BC Sniper 規則 ───────────────────────────────────────────────────

    @Test
    void noThemeVetoWhenRequireThemeIsTrue() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .hasTheme(false).build());
        assertTrue(result.vetoed());
        assertTrue(result.reasons().contains("NO_THEME"));
    }

    @Test
    void hasThemeShouldNotTriggerNoTheme() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder().build());
        assertFalse(result.reasons().contains("NO_THEME"));
    }

    @Test
    void codexScoreLowVeto() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .codexScore(new BigDecimal("5.0")).build());
        assertTrue(result.vetoed());
        assertTrue(result.reasons().contains("CODEX_SCORE_LOW"));
    }

    @Test
    void codexScoreAboveMinShouldPass() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .codexScore(new BigDecimal("7.0")).build());
        assertFalse(result.reasons().contains("CODEX_SCORE_LOW"));
    }

    @Test
    void themeNotInTopVeto() {
        // themeRank > 2 → veto
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .themeRank(3).build());
        assertTrue(result.vetoed());
        assertTrue(result.reasons().contains("THEME_NOT_IN_TOP"));
    }

    @Test
    void themeRankWithinLimitShouldPass() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .themeRank(2).build());
        assertFalse(result.reasons().contains("THEME_NOT_IN_TOP"));
    }

    @Test
    void themeScoreTooLowVeto() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .finalThemeScore(new BigDecimal("6.0")).build());
        assertTrue(result.vetoed());
        assertTrue(result.reasons().contains("THEME_SCORE_TOO_LOW"));
    }

    @Test
    void scoreDivergenceHighVeto() {
        // |java - claude| = |9.0 - 6.0| = 3.0 >= 2.5 → veto
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .javaScore(new BigDecimal("9.0"))
                .claudeScore(new BigDecimal("6.0")).build());
        assertTrue(result.vetoed());
        assertTrue(result.reasons().contains("SCORE_DIVERGENCE_HIGH"));
    }

    @Test
    void scoreDivergenceBelowMaxShouldPass() {
        // |9.0 - 7.0| = 2.0 < 2.5 → pass
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .javaScore(new BigDecimal("9.0"))
                .claudeScore(new BigDecimal("7.0")).build());
        assertFalse(result.reasons().contains("SCORE_DIVERGENCE_HIGH"));
    }

    @Test
    void volumeSpikeWithoutBreakoutVeto() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .volumeSpike(true).priceNotBreakHigh(true).build());
        assertTrue(result.vetoed());
        assertTrue(result.reasons().contains("VOLUME_SPIKE_NO_BREAKOUT"));
    }

    @Test
    void volumeSpikeButPriceBreakHighShouldPass() {
        // 有量且有突破 → 不 veto
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .volumeSpike(true).priceNotBreakHigh(false).build());
        assertFalse(result.reasons().contains("VOLUME_SPIKE_NO_BREAKOUT"));
    }

    @Test
    void entryTooExtendedVeto() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder()
                .entryTooExtended(true).build());
        assertTrue(result.vetoed());
        assertTrue(result.reasons().contains("ENTRY_TOO_EXTENDED"));
    }

    @Test
    void cleanCandidateShouldNotBeVetoed() {
        VetoEngine.VetoResult result = engine.evaluate(baseInputBuilder().build());
        assertFalse(result.vetoed(),
                "乾淨候選股不應被 veto，實際原因：" + result.reasons());
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    /** 返回一個「完全合格」的 VetoInput Builder（預設不觸發任何 veto） */
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
