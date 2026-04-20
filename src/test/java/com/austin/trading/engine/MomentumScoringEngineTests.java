package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class MomentumScoringEngineTests {

    private ScoreConfigService config;
    private MomentumScoringEngine engine;
    private MomentumCandidateEngine candidateEngine;

    @BeforeEach
    void setUp() {
        config = Mockito.mock(ScoreConfigService.class);
        // 預設回 default
        when(config.getDecimal(anyString(), Mockito.any(BigDecimal.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        when(config.getInt(anyString(), Mockito.anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(config.getString(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(config.getBoolean(anyString(), Mockito.anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1));

        engine = new MomentumScoringEngine(config);
        candidateEngine = new MomentumCandidateEngine(config);
    }

    /** 聯電案例：日漲 +8.77%、主流族群 rank=1、Claude 7.5、Codex 7.0、連續 3 日上漲、站上 5MA */
    @Test
    void lianDian_style_shouldScoreAboveEntryThreshold() {
        MomentumScoringEngine.MomentumInput in = new MomentumScoringEngine.MomentumInput(
                "2303",
                8.77,          // todayChangePct
                3,             // consecutiveUpDays
                true,          // todayNewHigh20
                2.5,           // volumeRatioTo5MA (爆量)
                false,         // volumeSpikeLongBlack
                1,             // themeRank = 主流
                new BigDecimal("8.5"),  // finalThemeScore
                new BigDecimal("7.5"),  // claudeScore
                new BigDecimal("7.0"),  // codexScore
                List.of(),              // no risk flags
                true,          // aboveMa5
                true           // ma5Turning
        );
        MomentumScoringEngine.MomentumResult result = engine.compute(in);

        assertThat(result.momentumScore()).isGreaterThanOrEqualTo(new BigDecimal("7.5"));
        assertThat(engine.classify(result.momentumScore()))
                .isIn(MomentumScoringEngine.MomentumTier.ENTER,
                      MomentumScoringEngine.MomentumTier.STRONG_ENTER);
    }

    /** 弱勢股：日跌 -1%、無題材、Claude 4.5 — 應 < 5 不進入 WATCH */
    @Test
    void weakStock_shouldBeBelowWatch() {
        MomentumScoringEngine.MomentumInput in = new MomentumScoringEngine.MomentumInput(
                "9999",
                -1.0, 0, false,
                0.8, false,
                null, null,
                new BigDecimal("4.5"), null,
                List.of(),
                false, false
        );
        MomentumScoringEngine.MomentumResult result = engine.compute(in);
        assertThat(result.momentumScore()).isLessThan(new BigDecimal("5.0"));
        assertThat(engine.classify(result.momentumScore()))
                .isEqualTo(MomentumScoringEngine.MomentumTier.BELOW_WATCH);
    }

    /** veto penalty 扣分後仍達門檻 */
    @Test
    void vetoPenalty_shouldBeSubtracted() {
        MomentumScoringEngine.MomentumInput in = new MomentumScoringEngine.MomentumInput(
                "2303",
                8.77, 3, true,
                2.5, false,
                1, new BigDecimal("8.5"),
                new BigDecimal("7.5"), new BigDecimal("7.0"),
                List.of(),
                true, true
        );
        MomentumScoringEngine.MomentumResult with    = engine.compute(in, new BigDecimal("1.5"));
        MomentumScoringEngine.MomentumResult without = engine.compute(in, BigDecimal.ZERO);
        assertThat(with.momentumScore())
                .isEqualByComparingTo(without.momentumScore().subtract(new BigDecimal("1.5")));
    }

    /** 聯電案例也應通過 candidate 基本篩選 */
    @Test
    void lianDian_style_shouldQualifyAsCandidate() {
        MomentumCandidateEngine.CandidateInput in = new MomentumCandidateEngine.CandidateInput(
                "2303",
                8.77, 3, true, true,
                true, true, true,
                2.5, true,
                1, new BigDecimal("8.5"),
                new BigDecimal("7.5"),
                List.of(),
                false
        );
        MomentumCandidateEngine.CandidateDecision d = candidateEngine.evaluate(in);
        assertThat(d.isMomentumCandidate()).isTrue();
        assertThat(d.matchedConditionsCount()).isGreaterThanOrEqualTo(4);
    }

    /** Codex veto 應排除，即使其他條件都對 */
    @Test
    void codexVetoed_shouldDisqualify() {
        MomentumCandidateEngine.CandidateInput in = new MomentumCandidateEngine.CandidateInput(
                "2303",
                8.77, 3, true, true,
                true, true, true,
                2.5, true,
                1, new BigDecimal("8.5"),
                new BigDecimal("7.5"),
                List.of(),
                true // codexVetoed!
        );
        MomentumCandidateEngine.CandidateDecision d = candidateEngine.evaluate(in);
        assertThat(d.aiStronglyNegative()).isTrue();
        assertThat(d.isMomentumCandidate()).isFalse();
    }

    /** 重大 risk flag 也應觸發強烈負評 */
    @Test
    void hardRiskFlag_shouldTriggerStrongNegative() {
        MomentumCandidateEngine.CandidateInput in = new MomentumCandidateEngine.CandidateInput(
                "2303",
                8.77, 3, true, true,
                true, true, true,
                2.5, true,
                1, new BigDecimal("8.5"),
                new BigDecimal("7.5"),
                List.of("LIQUIDITY_TRAP"),
                false
        );
        MomentumCandidateEngine.CandidateDecision d = candidateEngine.evaluate(in);
        assertThat(d.aiStronglyNegative()).isTrue();
        assertThat(d.isMomentumCandidate()).isFalse();
    }

    /** 無歷史 OHLC 時（MA 全 null、volume ratio null）仍可計分，使用中性值 */
    @Test
    void noHistory_shouldUseNeutralScores() {
        MomentumScoringEngine.MomentumInput in = new MomentumScoringEngine.MomentumInput(
                "5469",
                5.0, null, null,
                null, false,
                1, null,
                new BigDecimal("7.2"), new BigDecimal("7.1"),
                List.of(),
                null, null
        );
        MomentumScoringEngine.MomentumResult result = engine.compute(in);
        // priceMomentum=3, volume=0.5(中性), theme=2, ai=2, structure=0.5 → ~8
        assertThat(result.momentumScore()).isGreaterThanOrEqualTo(new BigDecimal("7.5"));
    }
}
