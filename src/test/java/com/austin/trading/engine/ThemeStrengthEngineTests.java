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

    // ── v2.8 P0 fix：Claude 尚未打分時不應誤判為 DECAY ─────────────────────

    @Test
    void claudeNotScored_decayRiskIsNeutral() {
        // 4/22 實例：mb=10 市場強勢，但 Claude heat/cont 尚未回填
        // 原 bug：decay=0.65 > 0.6 → DECAY、tradable=false
        // 修後：decay=0.30 中性，不觸發 DECAY gate
        ThemeStrengthInput in = new ThemeStrengthInput(DATE, "UNSCORED",
                BigDecimal.TEN,  // marketBehaviorScore (mb)
                null,            // breadthScore
                null,            // catalystHintScore
                null,            // claudeHeatScore — null
                null,            // claudeContinuationScore — null
                "政策",
                false);          // hasRiskFlag
        ThemeStrengthDecision d = engine.evaluate(in, null);

        assertThat(d.decayRisk()).isEqualByComparingTo(new BigDecimal("0.30"));
        assertThat(d.themeStage()).isNotEqualTo(ThemeStrengthEngine.STAGE_DECAY);
    }

    @Test
    void claudeNotScored_mb10_isTradable() {
        // mb=10 單獨貢獻 strength=4.0，claude 都 null 不應再打壓
        // 修後應 tradable=true（strength 4.0 > 3.0, decay 0.30 < 0.6）
        ThemeStrengthInput in = new ThemeStrengthInput(DATE, "MB10_UNSCORED",
                BigDecimal.TEN, null, null, null, null, "政策", false);
        ThemeStrengthDecision d = engine.evaluate(in, null);

        assertThat(d.tradable()).isTrue();
    }

    @Test
    void claudeNotScored_butRiskFlag_stillPenalized() {
        // 即使 Claude 沒打分，有 risk flag 時仍走原 formula（保留風險控制）
        ThemeStrengthInput in = new ThemeStrengthInput(DATE, "RISK_UNSCORED",
                BigDecimal.TEN, null, null, null, null, "政策", true);
        ThemeStrengthDecision d = engine.evaluate(in, null);

        // hasRiskFlag=true → 走原 formula: base=0.40 + heatPenalty=0.25 + riskPenalty=0.35 = 1.0
        assertThat(d.decayRisk()).isGreaterThan(new BigDecimal("0.6"));
    }

    @Test
    void claudePartiallyScored_usesFormula() {
        // Claude 只給 heat 分，cont 還沒給 → 有 AI 分數就走 formula
        ThemeStrengthInput in = new ThemeStrengthInput(DATE, "HEAT_ONLY",
                bd(8), null, null,
                bd(7),     // heat 有
                null,      // cont 仍 null
                "政策", false);
        ThemeStrengthDecision d = engine.evaluate(in, null);

        // cont=0（null 走 safe()=0）, heat=7 → base=0.40, heatPenalty=0 (heat≥3), decay=0.40
        assertThat(d.decayRisk()).isEqualByComparingTo(new BigDecimal("0.40"));
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
