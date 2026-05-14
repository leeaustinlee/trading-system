package com.austin.trading.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RiskRewardShadowGateServiceTest {

    private final RiskRewardShadowGateService service = new RiskRewardShadowGateService();

    @Test
    void evaluatePassesWhenRiskRewardMeetsDefaultMinimum() {
        var result = service.evaluate(new RiskRewardShadowGateService.PriceSnapshot(
                "2330", "SETUP", bd("100"), bd("95"), bd("110"), null));

        assertThat(result.shadowStatus()).isEqualTo(RiskRewardShadowGateService.PASS);
        assertThat(result.rrValue()).isEqualByComparingTo("2.0000");
        assertThat(result.minRequiredRr()).isEqualByComparingTo("1.8");
    }

    @Test
    void evaluateFailsWhenMomentumRiskRewardBelowMinimum() {
        var result = service.evaluate(new RiskRewardShadowGateService.PriceSnapshot(
                "2317", "MOMENTUM_CHASE", bd("100"), bd("95"), bd("109"), null));

        assertThat(result.shadowStatus()).isEqualTo(RiskRewardShadowGateService.FAIL);
        assertThat(result.rrValue()).isEqualByComparingTo("1.8000");
        assertThat(result.minRequiredRr()).isEqualByComparingTo("2.0");
    }

    @Test
    void evaluateReturnsDataGapWhenRequiredPriceMissing() {
        var result = service.evaluate(new RiskRewardShadowGateService.PriceSnapshot(
                "2454", "SETUP", bd("100"), null, bd("110"), null));

        assertThat(result.shadowStatus()).isEqualTo(RiskRewardShadowGateService.DATA_GAP);
        assertThat(result.rrValue()).isNull();
        assertThat(result.reason()).contains("DATA_GAP");
    }

    private BigDecimal bd(String value) {
        return new BigDecimal(value);
    }
}
