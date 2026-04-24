package com.austin.trading.engine;

import com.austin.trading.domain.enums.CrowdingRisk;
import com.austin.trading.domain.enums.RotationSignal;
import com.austin.trading.domain.enums.ThemeRole;
import com.austin.trading.domain.enums.TrendStage;
import com.austin.trading.dto.internal.GateTraceRecordDto;
import com.austin.trading.dto.internal.GateTraceRecordDto.Result;
import com.austin.trading.dto.internal.ThemeContextDto;
import com.austin.trading.dto.internal.ThemeGateTraceResultDto;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v2 Theme Engine PR4：ThemeGateTraceEngine 8-gate 單元測試。
 */
class ThemeGateTraceEngineTests {

    private ScoreConfigService config;
    private ThemeGateTraceEngine engine;

    @BeforeEach
    void setUp() {
        config = mock(ScoreConfigService.class);
        when(config.getDecimal(eq("theme.gate.strength_min"), any())).thenReturn(new BigDecimal("7.0"));
        when(config.getDecimal(eq("theme.gate.entry_strength_min"), any())).thenReturn(new BigDecimal("7.5"));
        when(config.getDecimal(eq("theme.gate.rr_min"), any())).thenReturn(new BigDecimal("2.0"));
        when(config.getDecimal(eq("theme.gate.max_score_divergence"), any())).thenReturn(new BigDecimal("2.5"));
        when(config.getDecimal(eq("theme.gate.min_liquidity_turnover"), any())).thenReturn(new BigDecimal("30000000"));
        when(config.getDecimal(eq("theme.gate.final_rank_a_min"), any())).thenReturn(new BigDecimal("7.6"));
        engine = new ThemeGateTraceEngine(config);
    }

    // ══════════════════════════════════════════════════════════════════

    @Test
    void gateOrder_isStrictAndComplete() {
        List<String> order = ThemeGateTraceEngine.gateOrder();
        assertThat(order).containsExactly(
                "G1_MARKET_REGIME",
                "G2_THEME_VETO",
                "G3_THEME_ROTATION",
                "G4_LIQUIDITY",
                "G5_SCORE_DIVERGENCE",
                "G6_RR",
                "G7_POSITION_SIZING",
                "G8_FINAL_RANK"
        );
    }

    @Test
    void allGreen_passes() {
        ThemeGateTraceResultDto r = engine.evaluate(healthyInput().build());
        assertThat(r.overallOutcome()).isEqualTo(Result.PASS);
        assertThat(r.gates()).hasSize(8);
        assertThat(r.gates()).allSatisfy(g -> assertThat(g.result()).isEqualTo(Result.PASS));
        assertThat(r.themeMultiplier()).isNotNull();
        assertThat(r.themeFinalScore()).isNotNull();
    }

    // ── G1 ────────────────────────────────────────────────────────────

    @Test
    void g1_marketRegimeUnknown_waits_butDownstreamRuns() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().marketRegime(null).build());
        assertThat(r.findGate("G1_MARKET_REGIME").result()).isEqualTo(Result.WAIT);
        // 非 BLOCK 不應短路 G2-G8
        assertThat(r.gates()).noneMatch(g -> g.result() == Result.SKIPPED);
    }

    @Test
    void g1_tradeNotAllowed_blocks_shortCircuitsRest() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().tradeAllowed(false).build());
        assertThat(r.findGate("G1_MARKET_REGIME").result()).isEqualTo(Result.BLOCK);
        assertThat(r.findGate("G1_MARKET_REGIME").reason()).isEqualTo("TRADE_NOT_ALLOWED");
        // G2-G8 should be SKIPPED
        assertThat(r.gates().stream().filter(g -> g.result() == Result.SKIPPED)).hasSize(7);
        assertThat(r.overallOutcome()).isEqualTo(Result.BLOCK);
    }

    // ── G2 ────────────────────────────────────────────────────────────

    @Test
    void g2_themeContextMissing_waits() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().themeContext(null).build());
        assertThat(r.findGate("G2_THEME_VETO").result()).isEqualTo(Result.WAIT);
        assertThat(r.findGate("G2_THEME_VETO").reason()).isEqualTo("THEME_CONTEXT_MISSING");
    }

    @Test
    void g2_strengthBelowMin_blocks() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().themeStrength(new BigDecimal("6.5")).build());
        assertThat(r.findGate("G2_THEME_VETO").result()).isEqualTo(Result.BLOCK);
        assertThat(r.findGate("G2_THEME_VETO").reason()).isEqualTo("THEME_STRENGTH_BELOW_MIN");
    }

    // ── G3 ────────────────────────────────────────────────────────────

    @Test
    void g3_rotationOut_blocks() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().rotationSignal(RotationSignal.OUT).build());
        assertThat(r.findGate("G3_THEME_ROTATION").result()).isEqualTo(Result.BLOCK);
        assertThat(r.findGate("G3_THEME_ROTATION").reason()).isEqualTo("THEME_ROTATION_OUT");
    }

    @Test
    void g3_rotationUnknown_waits() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().rotationSignal(RotationSignal.UNKNOWN).build());
        assertThat(r.findGate("G3_THEME_ROTATION").result()).isEqualTo(Result.WAIT);
    }

    @Test
    void g3_strengthBetweenG2AndEntryMin_withRotationIn_waits() {
        // G2 strength_min=7.0 → PASS；G3 entry_strength_min=7.5；rotation=IN → themeStrength 7.2 WAIT
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().themeStrength(new BigDecimal("7.2")).build());
        assertThat(r.findGate("G2_THEME_VETO").result()).isEqualTo(Result.PASS);
        assertThat(r.findGate("G3_THEME_ROTATION").result()).isEqualTo(Result.WAIT);
        assertThat(r.findGate("G3_THEME_ROTATION").reason()).isEqualTo("THEME_STRENGTH_BELOW_ENTRY_MIN");
    }

    @Test
    void g3_rotationNone_lowStrength_passes_noEntryMinApplied() {
        // rotation=NONE 視為 neutral，不套用 entry_strength_min；即使 themeStrength 7.2 也應 PASS
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput()
                        .rotationSignal(RotationSignal.NONE)
                        .themeStrength(new BigDecimal("7.2"))
                        .build());
        assertThat(r.findGate("G3_THEME_ROTATION").result()).isEqualTo(Result.PASS);
    }

    // ── G4 ────────────────────────────────────────────────────────────

    @Test
    void g4_turnoverBelowMin_blocks() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().avgTurnover(new BigDecimal("5000000")).build());   // 5M < 30M
        assertThat(r.findGate("G4_LIQUIDITY").result()).isEqualTo(Result.BLOCK);
        assertThat(r.findGate("G4_LIQUIDITY").reason()).isEqualTo("LIQUIDITY_BELOW_MIN");
    }

    @Test
    void g4_turnoverMissing_waits() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().avgTurnover(null).build());
        assertThat(r.findGate("G4_LIQUIDITY").result()).isEqualTo(Result.WAIT);
    }

    // ── G5 ────────────────────────────────────────────────────────────

    @Test
    void g5_divergenceHigh_blocks() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput()
                        .javaScore(new BigDecimal("9.0"))
                        .claudeScore(new BigDecimal("5.0"))   // diff 4.0 > 2.5
                        .codexScore(new BigDecimal("7.5"))
                        .build());
        assertThat(r.findGate("G5_SCORE_DIVERGENCE").result()).isEqualTo(Result.BLOCK);
        assertThat(r.findGate("G5_SCORE_DIVERGENCE").reason()).isEqualTo("SCORE_DIVERGENCE_HIGH");
    }

    @Test
    void g5_onlyOneScore_waits() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput()
                        .javaScore(new BigDecimal("8.0"))
                        .claudeScore(null)
                        .codexScore(null)
                        .build());
        assertThat(r.findGate("G5_SCORE_DIVERGENCE").result()).isEqualTo(Result.WAIT);
    }

    // ── G6 ────────────────────────────────────────────────────────────

    @Test
    void g6_rrBelowMin_blocks() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().rr(new BigDecimal("1.5")).build());    // < 2.0
        assertThat(r.findGate("G6_RR").result()).isEqualTo(Result.BLOCK);
    }

    // ── G7 ────────────────────────────────────────────────────────────

    @Test
    void g7_portfolioFull_blocks() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().openPositions(4).maxPositions(4).build());
        assertThat(r.findGate("G7_POSITION_SIZING").result()).isEqualTo(Result.BLOCK);
        assertThat(r.findGate("G7_POSITION_SIZING").reason()).isEqualTo("PORTFOLIO_FULL");
    }

    @Test
    void g7_cashZero_blocks() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().availableCash(BigDecimal.ZERO).build());
        assertThat(r.findGate("G7_POSITION_SIZING").result()).isEqualTo(Result.BLOCK);
    }

    // ── G8 ────────────────────────────────────────────────────────────

    @Test
    void g8_themeFinalBelowMin_blocks() {
        // baseScore=5.0, themeStrength=7.5 → multiplier = 0.6 + 0.04*7.5 = 0.9 → themeFinal=4.5 < 7.6
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().baseScore(new BigDecimal("5.0")).build());
        assertThat(r.findGate("G8_FINAL_RANK").result()).isEqualTo(Result.BLOCK);
        assertThat(r.themeFinalScore()).isEqualByComparingTo(new BigDecimal("4.5000"));
    }

    @Test
    void g8_earlyStageBonusApplied() {
        // stage EARLY → +0.05；baseScore=8.0 themeStrength=7.5 → mult=0.95 → themeFinal=7.6
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput()
                        .baseScore(new BigDecimal("8.0"))
                        .trendStage(TrendStage.EARLY)
                        .build());
        assertThat(r.themeMultiplier()).isEqualByComparingTo(new BigDecimal("0.9500"));
        assertThat(r.themeFinalScore()).isEqualByComparingTo(new BigDecimal("7.6000"));
    }

    @Test
    void g8_lateStagePenaltyApplied() {
        // stage LATE → -0.05；baseScore=8.0 themeStrength=7.5 → mult=0.85 → themeFinal=6.8
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput()
                        .baseScore(new BigDecimal("8.0"))
                        .trendStage(TrendStage.LATE)
                        .build());
        assertThat(r.themeMultiplier()).isEqualByComparingTo(new BigDecimal("0.8500"));
        assertThat(r.themeFinalScore()).isEqualByComparingTo(new BigDecimal("6.8000"));
    }

    // ── crowding size factor (§4.6) ──────────────────────────────────

    @Test
    void sizeFactor_lowMidHigh_correct() {
        ThemeGateTraceResultDto low = engine.evaluate(
                healthyInput().crowdingRisk(CrowdingRisk.LOW).build());
        ThemeGateTraceResultDto mid = engine.evaluate(
                healthyInput().crowdingRisk(CrowdingRisk.MID).build());
        ThemeGateTraceResultDto high = engine.evaluate(
                healthyInput().crowdingRisk(CrowdingRisk.HIGH).build());
        assertThat(low.themeSizeFactor()).isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(mid.themeSizeFactor()).isEqualByComparingTo(new BigDecimal("0.85"));
        assertThat(high.themeSizeFactor()).isEqualByComparingTo(new BigDecimal("0.65"));
    }

    // ── short circuit behavior ───────────────────────────────────────

    @Test
    void blockAtG2_shortCircuitsG3ToG8() {
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().themeStrength(new BigDecimal("5.0")).build());
        assertThat(r.findGate("G2_THEME_VETO").result()).isEqualTo(Result.BLOCK);
        // G3-G8 should be SKIPPED
        List<GateTraceRecordDto> skipped = r.gates().stream()
                .filter(g -> g.result() == Result.SKIPPED).toList();
        assertThat(skipped).hasSize(6);
        assertThat(skipped).allSatisfy(g ->
                assertThat(g.reason()).isEqualTo("SHORT_CIRCUITED_AFTER_BLOCK"));
    }

    @Test
    void multipleWaits_overallIsWait_noBlock() {
        // G2 theme missing + G4 turnover missing → 兩個 WAIT，沒 BLOCK
        ThemeGateTraceResultDto r = engine.evaluate(
                healthyInput().themeContext(null).avgTurnover(null).build());
        assertThat(r.overallOutcome()).isEqualTo(Result.WAIT);
        assertThat(r.gates().stream().filter(g -> g.result() == Result.BLOCK)).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════
    // builder
    // ══════════════════════════════════════════════════════════════════

    private Builder healthyInput() {
        return new Builder();
    }

    /** 預設一個「全綠」的 input，測試只改單一欄位。 */
    private static class Builder {
        String symbol = "2454";
        String marketRegime = "BULL_TREND";
        boolean tradeAllowed = true;
        BigDecimal riskMultiplier = new BigDecimal("1.0");
        ThemeContextDto themeContext;
        boolean contextExplicitlySet = false;    // 區分「未設定 → 用預設」vs「明確設為 null」
        BigDecimal avgTurnover = new BigDecimal("500000000");
        BigDecimal volumeRatio = new BigDecimal("1.2");
        BigDecimal javaScore = new BigDecimal("8.0");
        BigDecimal claudeScore = new BigDecimal("8.2");
        BigDecimal codexScore = new BigDecimal("8.1");
        BigDecimal rr = new BigDecimal("2.5");
        BigDecimal baseScore = new BigDecimal("8.5");
        int openPositions = 1;
        int maxPositions = 4;
        BigDecimal availableCash = new BigDecimal("100000");

        BigDecimal themeStrength = new BigDecimal("7.5");
        TrendStage trendStage = TrendStage.MID;
        RotationSignal rotationSignal = RotationSignal.IN;
        CrowdingRisk crowdingRisk = CrowdingRisk.LOW;

        Builder symbol(String v) { this.symbol = v; return this; }
        Builder marketRegime(String v) { this.marketRegime = v; return this; }
        Builder tradeAllowed(boolean v) { this.tradeAllowed = v; return this; }
        Builder themeContext(ThemeContextDto v) {
            this.themeContext = v; this.contextExplicitlySet = true; return this;
        }
        Builder themeStrength(BigDecimal v) { this.themeStrength = v; return this; }
        Builder trendStage(TrendStage v) { this.trendStage = v; return this; }
        Builder rotationSignal(RotationSignal v) { this.rotationSignal = v; return this; }
        Builder crowdingRisk(CrowdingRisk v) { this.crowdingRisk = v; return this; }
        Builder avgTurnover(BigDecimal v) { this.avgTurnover = v; return this; }
        Builder javaScore(BigDecimal v) { this.javaScore = v; return this; }
        Builder claudeScore(BigDecimal v) { this.claudeScore = v; return this; }
        Builder codexScore(BigDecimal v) { this.codexScore = v; return this; }
        Builder rr(BigDecimal v) { this.rr = v; return this; }
        Builder baseScore(BigDecimal v) { this.baseScore = v; return this; }
        Builder openPositions(int v) { this.openPositions = v; return this; }
        Builder maxPositions(int v) { this.maxPositions = v; return this; }
        Builder availableCash(BigDecimal v) { this.availableCash = v; return this; }

        ThemeGateTraceEngine.Input build() {
            ThemeContextDto ctx;
            if (contextExplicitlySet) {
                ctx = themeContext;   // 包含 null 情境
            } else {
                ctx = new ThemeContextDto(
                        symbol, "AI_SERVER",
                        themeStrength,
                        trendStage,
                        rotationSignal,
                        new BigDecimal("7.0"), new BigDecimal("7.0"),
                        crowdingRisk,
                        new BigDecimal("0.85"),
                        List.of("NEWS"),
                        ThemeRole.LEADER, null, null, null, null, null
                );
            }
            return new ThemeGateTraceEngine.Input(
                    symbol, marketRegime, tradeAllowed, riskMultiplier,
                    ctx, avgTurnover, volumeRatio,
                    javaScore, claudeScore, codexScore,
                    rr, baseScore, openPositions, maxPositions, availableCash
            );
        }
    }
}
