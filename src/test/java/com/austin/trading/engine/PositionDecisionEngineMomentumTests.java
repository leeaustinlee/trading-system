package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class PositionDecisionEngineMomentumTests {

    private ScoreConfigService config;
    private PositionDecisionEngine engine;

    @BeforeEach
    void setUp() {
        config = Mockito.mock(ScoreConfigService.class);
        when(config.getDecimal(anyString(), Mockito.any(BigDecimal.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        when(config.getInt(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(config.getBoolean(anyString(), Mockito.anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1));
        engine = new PositionDecisionEngine(config);
    }

    private PositionDecisionEngine.PositionDecisionInput momentumInput(
            double entry, double current, int holdingDays,
            boolean belowMa5, boolean volumeSpikeLongBlack, String marketGrade
    ) {
        return new PositionDecisionEngine.PositionDecisionInput(
                "2303",
                BigDecimal.valueOf(entry),
                null, null, null, null,
                "LONG",
                holdingDays,
                BigDecimal.valueOf(current),
                null, null, null, null,
                marketGrade, 1, new BigDecimal("8.0"),
                BigDecimal.valueOf((current - entry) / entry * 100.0),
                PositionDecisionEngine.ExtendedLevel.NONE,
                false, false, true, false, false,
                "MOMENTUM_CHASE", belowMa5, volumeSpikeLongBlack
        );
    }

    /** 跌破 5MA → MOMENTUM_COLLAPSE 立即出場 */
    @Test
    void belowMa5_shouldExitWithMomentumCollapse() {
        var in = momentumInput(100.0, 98.5, 1, true, false, "B");
        var r = engine.evaluate(in);
        assertThat(r.status()).isEqualTo(PositionDecisionEngine.PositionStatus.EXIT);
        assertThat(r.reason()).contains("MOMENTUM_COLLAPSE").contains("5MA");
    }

    /** 爆量長黑 → MOMENTUM_COLLAPSE */
    @Test
    void volumeSpikeLongBlack_shouldExit() {
        var in = momentumInput(100.0, 99.0, 1, false, true, "B");
        var r = engine.evaluate(in);
        assertThat(r.status()).isEqualTo(PositionDecisionEngine.PositionStatus.EXIT);
        assertThat(r.reason()).contains("MOMENTUM_COLLAPSE").contains("爆量長黑");
    }

    /** 持有 3 日未達 TP1 → TIME_STOP */
    @Test
    void heldOver3Days_shouldTimeStop() {
        var in = momentumInput(100.0, 102.0, 3, false, false, "B");
        var r = engine.evaluate(in);
        assertThat(r.status()).isEqualTo(PositionDecisionEngine.PositionStatus.EXIT);
        assertThat(r.reason()).contains("TIME_STOP");
    }

    /** 大盤 C 級 → EMERGENCY_EXIT */
    @Test
    void marketGradeC_shouldEmergencyExit() {
        var in = momentumInput(100.0, 100.5, 1, false, false, "C");
        var r = engine.evaluate(in);
        assertThat(r.status()).isEqualTo(PositionDecisionEngine.PositionStatus.EXIT);
        assertThat(r.reason()).contains("EMERGENCY_EXIT");
    }

    /** 跌破 Momentum 停損 -2.5% → MOMENTUM_STOP_LOSS */
    @Test
    void belowMomentumStop_shouldExit() {
        // entry=100、current=97.4（-2.6% < -2.5% threshold）
        var in = momentumInput(100.0, 97.4, 1, false, false, "B");
        var r = engine.evaluate(in);
        assertThat(r.status()).isEqualTo(PositionDecisionEngine.PositionStatus.EXIT);
        assertThat(r.reason()).contains("MOMENTUM_STOP_LOSS");
    }

    /** Momentum 正常持有（未觸發任何出場條件）→ 走回原 Setup 邏輯 */
    @Test
    void normalMomentumHolding_shouldFallThroughToSetupFlow() {
        var in = momentumInput(100.0, 101.5, 1, false, false, "B");
        var r = engine.evaluate(in);
        // 不會 EXIT（原 Setup 邏輯判斷）
        assertThat(r.status()).isNotEqualTo(PositionDecisionEngine.PositionStatus.EXIT);
    }

    /** Setup 持倉不該受 Momentum 規則影響 */
    @Test
    void setupPosition_shouldNotApplyMomentumRules() {
        // belowMa5=true 但 strategyType=SETUP，應該不會因此 EXIT
        var in = new PositionDecisionEngine.PositionDecisionInput(
                "00631L",
                BigDecimal.valueOf(22.81),
                BigDecimal.valueOf(21.21),
                null, null, null,
                "LONG",
                1,
                BigDecimal.valueOf(22.50),
                null, null, null, null,
                "B", 1, new BigDecimal("7.0"),
                new BigDecimal("-1.36"),
                PositionDecisionEngine.ExtendedLevel.NONE,
                false, false, true, false, false,
                "SETUP", true, true  // belowMa5 與 volumeSpikeLongBlack 對 SETUP 無效
        );
        var r = engine.evaluate(in);
        assertThat(r.reason()).doesNotContain("MOMENTUM");
    }
}
