package com.austin.trading.engine;

import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PricePlanSanityEngineTest {

    @Test
    void passWhenPlanIsOrderedAndRrAboveThreshold() {
        PricePlanSanityEngine engine = new PricePlanSanityEngine(config());
        var result = engine.evaluate(new PricePlanSanityEngine.Input(
                bd("100"), bd("95"), bd("110"), bd("118"), "SETUP", false));
        assertThat(result.accepted()).isTrue();
        assertThat(result.status()).isEqualTo("PASS");
        assertThat(result.rrRatio()).isEqualByComparingTo("2.0000");
    }

    @Test
    void shadowRejectsInvalidTargetsWithoutBlockingProductionPath() {
        PricePlanSanityEngine engine = new PricePlanSanityEngine(config());
        var result = engine.evaluate(new PricePlanSanityEngine.Input(
                bd("100"), bd("99"), bd("100.5"), bd("100.4"), "SETUP", false));
        assertThat(result.accepted()).isFalse();
        assertThat(result.status()).isEqualTo("SHADOW_REJECT");
        assertThat(result.violations()).contains(
                PricePlanViolation.TP2_NOT_ABOVE_TP1,
                PricePlanViolation.RR_BELOW_THRESHOLD,
                PricePlanViolation.TP1_GAIN_TOO_SMALL,
                PricePlanViolation.STOP_TOO_CLOSE);
    }

    private ScoreConfigService config() {
        ScoreConfigService cfg = mock(ScoreConfigService.class);
        when(cfg.getBoolean(eq("price_plan.sanity.enabled"), anyBoolean())).thenReturn(true);
        when(cfg.getBoolean(eq("price_plan.sanity.shadow_only"), anyBoolean())).thenReturn(true);
        when(cfg.getDecimal(eq("price_plan.min_rr.setup"), any())).thenReturn(bd("1.8"));
        when(cfg.getDecimal(eq("price_plan.min_rr.momentum"), any())).thenReturn(bd("2.0"));
        when(cfg.getDecimal(eq("price_plan.tp1_min_gain_pct"), any())).thenReturn(bd("3.0"));
        when(cfg.getDecimal(eq("price_plan.stop_min_loss_pct"), any())).thenReturn(bd("1.5"));
        when(cfg.getDecimal(eq("price_plan.stop_max_loss_pct"), any())).thenReturn(bd("10.0"));
        return cfg;
    }

    private BigDecimal bd(String v) { return new BigDecimal(v); }
}
