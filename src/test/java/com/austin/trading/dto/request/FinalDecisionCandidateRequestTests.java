package com.austin.trading.dto.request;

import com.austin.trading.domain.enums.MarketSession;
import com.austin.trading.engine.PriceGateEvaluator;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class FinalDecisionCandidateRequestTests {

    @Test
    void copyWithScores_keepsOverlayAndPriceGateFields() {
        FinalDecisionCandidateRequest c = candidate();

        FinalDecisionCandidateRequest copied = c.copyWithScores(
                bd("8.1"), bd("7.5"), bd("9.0"), bd("8.0"), false, bd("8.2"), bd("0.1"));

        assertThat(copied.currentPrice()).isEqualByComparingTo("100");
        assertThat(copied.openPrice()).isEqualByComparingTo("101");
        assertThat(copied.previousClose()).isEqualByComparingTo("102");
        assertThat(copied.vwapPrice()).isEqualByComparingTo("99");
        assertThat(copied.volumeRatio()).isEqualByComparingTo("1.5");
        assertThat(copied.marketRegime()).isEqualTo("BULL_TREND");
        assertThat(copied.dayHigh()).isEqualByComparingTo("101");
        assertThat(copied.belowOpen()).isTrue();
        assertThat(copied.belowPrevClose()).isTrue();
        assertThat(copied.nearDayHigh()).isTrue();
        assertThat(copied.tradabilityTag()).isEqualTo("可回測進場候選");
    }

    @Test
    void priceGateTraceUsesCopiedCandidateValues() {
        PriceGateEvaluator evaluator = new PriceGateEvaluator(new DefaultScoreConfigService());

        FinalDecisionCandidateRequest copied = candidate().copyWithScores(
                bd("8"), null, null, bd("8"), false, bd("8"), bd("0"));

        var decision = evaluator.evaluate(copied, MarketSession.LIVE_TRADING);

        assertThat(decision.trace().get("currentPrice")).isEqualTo(bd("100"));
        assertThat(decision.trace().get("openPrice")).isEqualTo(bd("101"));
        assertThat(decision.trace().get("previousClose")).isEqualTo(bd("102"));
        assertThat(decision.trace().get("marketRegime")).isEqualTo("BULL_TREND");
    }

    private static FinalDecisionCandidateRequest candidate() {
        return new FinalDecisionCandidateRequest(
                "2330", "台積電", "合理", "BREAKOUT", 1.8,
                true, true, false, true, true, true, true,
                "r", "98-100", 95.0, 110.0, 120.0,
                null, null, null, null, false, bd("8"), true, 1, bd("9"),
                null, null, false, false, false, true,
                bd("100"), bd("101"), bd("102"), bd("99"), bd("1.5"),
                bd("-0.0099"), bd("0.0196"), "BULL_TREND", bd("101"), "可回測進場候選");
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    static class DefaultScoreConfigService extends ScoreConfigService {
        DefaultScoreConfigService() { super(null); }
        @Override public BigDecimal getDecimal(String key, BigDecimal defaultValue) { return defaultValue; }
        @Override public int getInt(String key, int defaultValue) { return defaultValue; }
        @Override public boolean getBoolean(String key, boolean defaultValue) { return defaultValue; }
    }
}
