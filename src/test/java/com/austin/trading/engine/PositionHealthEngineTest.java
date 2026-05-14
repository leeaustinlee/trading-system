package com.austin.trading.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PositionHealthEngineTest {

    private final PositionHealthEngine engine = new PositionHealthEngine();

    @Test
    void bullAlignedOutperformanceReturnsHoldTier() {
        var result = engine.evaluate(new PositionHealthInput(
                "2330", bd("100"), bd("112"), bd("110"), bd("105"), bd("100"), bd("108"),
                bd("99"), bd("113"), bd("3"), bd("1.4"),
                bd("8"), bd("2"), bd("12"), bd("4"), "MAINSTREAM", true, "UNKNOWN"));
        assertThat(result.healthScore()).isGreaterThanOrEqualTo(70);
        assertThat(result.exitTier()).isEqualTo(PositionHealthResult.ExitTier.HOLD);
        assertThat(result.structureStatus()).isEqualTo("BULL_ALIGNED");
    }

    @Test
    void missingMaAndVolumeAreReportedAsDataGaps() {
        var result = engine.evaluate(new PositionHealthInput(
                "2330", bd("100"), bd("98"), null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null, null));
        assertThat(result.dataGaps()).anyMatch(s -> s.contains("MA5"));
        assertThat(result.dataGaps()).anyMatch(s -> s.contains("volume"));
        assertThat(result.chipStatus()).isEqualTo("UNKNOWN");
    }

    private BigDecimal bd(String v) { return new BigDecimal(v); }
}
