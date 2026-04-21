package com.austin.trading.engine;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.ThemeStrengthDecision;
import com.austin.trading.dto.internal.ThemeStrengthInput;
import com.austin.trading.service.ScoreConfigService;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class ThemeStrengthEngineTests {

    private ThemeStrengthEngine engine;
    private static final LocalDate DATE = LocalDate.of(2026, 4, 21);

    @BeforeEach
    void setUp() {
        ScoreConfigService config = Mockito.mock(ScoreConfigService.class);
        // Default weights from spec
        when(config.getDecimal(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        engine = new ThemeStrengthEngine(config, new ObjectMapper());
    }

    // ── Null guard ────────────────────────────────────────────────────────────

    @Test
    void nullInput_returnsNull() {
        assertThat(engine.evaluate(null, null)).isNull();
    }

    // ── Score formula ─────────────────────────────────────────────────────────

    @Test
    void strongTheme_producesHighStrengthScore() {
        ThemeStrengthInput in = input("AI", bd(8), bd(9), bd(8), bd(7), false);
        ThemeStrengthDecision d = engine.evaluate(in, null);

        assertThat(d.strengthScore()).isGreaterThan(BigDecimal.valueOf(7.0));
    }

    @Test
    void weakTheme_producesLowStrengthScore() {
        ThemeStrengthInput in = input("WEAK", bd(1), bd(1), bd(1), bd(1), false);
        ThemeStrengthDecision d = engine.evaluate(in, null);

        assertThat(d.strengthScore()).isLessThan(BigDecimal.valueOf(3.0));
    }

    @Test
    void scoreIsCappedAtTen() {
        ThemeStrengthInput in = input("X", bd(10), bd(10), bd(10), bd(10), false);
        ThemeStrengthDecision d = engine.evaluate(in, null);

        assertThat(d.strengthScore()).isLessThanOrEqualTo(BigDecimal.TEN);
    }

    @Test
    void nullInputScores_fallBackToZero() {
        ThemeStrengthInput in = new ThemeStrengthInput(DATE, "NULL_THEME",
                null, null, null, null, null, null, false);
        ThemeStrengthDecision d = engine.evaluate(in, null);

        assertThat(d.strengthScore()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── Theme stage ───────────────────────────────────────────────────────────

    @Test
    void lowScore_classifiedAsDecay() {
        ThemeStrengthInput in = input("DECAY", bd(1), bd(1), bd(1), bd(1), false);
        assertThat(engine.evaluate(in, null).themeStage()).isEqualTo(ThemeStrengthEngine.STAGE_DECAY);
    }

    @Test
    void midScore_classifiedAsEarlyExpansion() {
        ThemeStrengthInput in = input("EARLY", bd(4), bd(5), bd(4), bd(4), false);
        String stage = engine.evaluate(in, null).themeStage();
        assertThat(stage).isIn(ThemeStrengthEngine.STAGE_EARLY_EXPANSION, ThemeStrengthEngine.STAGE_MID_TREND);
    }

    @Test
    void highScore_classifiedAsMidTrendOrLate() {
        ThemeStrengthInput in = input("HIGH", bd(7), bd(8), bd(8), bd(6), false);
        String stage = engine.evaluate(in, null).themeStage();
        assertThat(stage).isIn(ThemeStrengthEngine.STAGE_MID_TREND, ThemeStrengthEngine.STAGE_LATE_EXTENSION);
    }

    // ── Tradability rules ─────────────────────────────────────────────────────

    @Test
    void weakTheme_notTradable() {
        ThemeStrengthInput in = input("WEAK", bd(1), bd(1), bd(1), bd(1), false);
        assertThat(engine.evaluate(in, null).tradable()).isFalse();
    }

    @Test
    void strongTheme_noRiskFlag_isTradable() {
        ThemeStrengthInput in = input("AI", bd(8), bd(9), bd(8), bd(7), false);
        assertThat(engine.evaluate(in, null).tradable()).isTrue();
    }

    @Test
    void riskFlag_withNonBullRegime_notTradable() {
        ThemeStrengthInput in = input("RISK_THEME", bd(7), bd(7), bd(7), bd(7), true);
        MarketRegimeDecision rangeChop = new MarketRegimeDecision(
                DATE, null, "RANGE_CHOP", "B", true,
                BigDecimal.valueOf(0.8), null, null, null, null);
        assertThat(engine.evaluate(in, rangeChop).tradable()).isFalse();
    }

    @Test
    void riskFlag_withBullTrend_isTradable() {
        ThemeStrengthInput in = input("RISK_BULL", bd(7), bd(7), bd(7), bd(7), true);
        MarketRegimeDecision bull = new MarketRegimeDecision(
                DATE, null, "BULL_TREND", "A", true,
                BigDecimal.valueOf(1.0), null, null, null, null);
        assertThat(engine.evaluate(in, bull).tradable()).isTrue();
    }

    // ── Decay risk ────────────────────────────────────────────────────────────

    @Test
    void lowContinuationAndRiskFlag_producesHighDecayRisk() {
        ThemeStrengthInput in = input("HIGH_DECAY", bd(8), bd(1), bd(1), bd(7), true);
        assertThat(engine.evaluate(in, null).decayRisk())
                .isGreaterThan(BigDecimal.valueOf(0.6));
    }

    @Test
    void highContinuationNoRiskFlag_producesLowDecayRisk() {
        ThemeStrengthInput in = input("LOW_DECAY", bd(8), bd(9), bd(8), bd(7), false);
        assertThat(engine.evaluate(in, null).decayRisk())
                .isLessThan(BigDecimal.valueOf(0.4));
    }

    // ── Output completeness ───────────────────────────────────────────────────

    @Test
    void output_containsThemeTagAndDate() {
        ThemeStrengthInput in = input("AI", bd(7), bd(8), bd(7), bd(6), false);
        ThemeStrengthDecision d = engine.evaluate(in, null);

        assertThat(d.themeTag()).isEqualTo("AI");
        assertThat(d.tradingDate()).isEqualTo(DATE);
    }

    @Test
    void output_reasonsJsonContainsKeyFields() {
        ThemeStrengthInput in = input("AI", bd(7), bd(8), bd(7), bd(6), false);
        ThemeStrengthDecision d = engine.evaluate(in, null);

        assertThat(d.reasonsJson()).contains("themeTag").contains("strengthScore").contains("tradable");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ThemeStrengthInput input(String tag, BigDecimal mb, BigDecimal heat,
                                     BigDecimal cont, BigDecimal breadth, boolean riskFlag) {
        return new ThemeStrengthInput(DATE, tag, mb, breadth, BigDecimal.valueOf(1.5),
                heat, cont, "政策", riskFlag);
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }
}
