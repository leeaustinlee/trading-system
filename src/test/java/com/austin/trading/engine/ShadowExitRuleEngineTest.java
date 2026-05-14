package com.austin.trading.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ShadowExitRuleEngineTest {

    private final ShadowExitRuleEngine engine = new ShadowExitRuleEngine();

    @Test
    void emitsMaAndHybridBreakSignals() {
        var result = engine.evaluate(new ShadowExitRuleEngine.Input(
                bd("100"), bd("96"), bd("95"), bd("98"), bd("97"), bd("94"), bd("2")));
        assertThat(result.ma5().action()).isEqualTo("MA5_BREAK");
        assertThat(result.ma10().action()).isEqualTo("MA10_BREAK");
        assertThat(result.hybrid().action()).isEqualTo("HYBRID_STOP");
    }

    @Test
    void dataGapWhenTechnicalInputsMissing() {
        var result = engine.evaluate(new ShadowExitRuleEngine.Input(
                bd("100"), bd("101"), null, null, null, null, null));
        assertThat(result.dataGaps()).isNotEmpty();
        assertThat(result.ma5().action()).isEqualTo("DATA_GAP");
    }

    private BigDecimal bd(String v) { return new BigDecimal(v); }
}
