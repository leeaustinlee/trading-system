package com.austin.trading.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class IntradayVwapServiceTests {

    private final IntradayVwapService service = new IntradayVwapService();

    @Test
    void computeFromCumulative_withValidTurnoverAndVolume_returnsVwap() {
        // 100,000 張 × 1000 = 1e8 股；成交金額 2.19e10 → vwap = 219.0 元
        IntradayVwapService.VwapResult result =
                service.computeFromCumulative(100_000L, 2.19e10);
        assertThat(result.available()).isTrue();
        assertThat(result.source()).isEqualTo("cumulative-mis-turnover");
        assertThat(result.price()).isEqualByComparingTo(new BigDecimal("219.00"));
        assertThat(result.reason()).isNull();
    }

    @Test
    void computeFromCumulative_realisticMediatekSample_returnsCloseToCurrentPrice() {
        // 2454 聯發科：假設累計 15,000 張、turnover=3.32e10 → vwap ≈ 2213 元
        IntradayVwapService.VwapResult result =
                service.computeFromCumulative(15_000L, 3.32e10);
        assertThat(result.price()).isEqualByComparingTo(new BigDecimal("2213.3333"));
    }

    @Test
    void computeFromCumulative_missingVolume_unavailable() {
        IntradayVwapService.VwapResult result = service.computeFromCumulative(null, 1.0e9);
        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isEqualTo("MISSING_VOLUME");
        assertThat(result.price()).isNull();
    }

    @Test
    void computeFromCumulative_missingTurnover_unavailable() {
        IntradayVwapService.VwapResult result = service.computeFromCumulative(1000L, null);
        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isEqualTo("MISSING_TURNOVER");
    }

    @Test
    void computeFromCumulative_zeroVolume_unavailable() {
        IntradayVwapService.VwapResult result = service.computeFromCumulative(0L, 1000.0);
        assertThat(result.available()).isFalse();
    }

    @Test
    void computeFromBars_notImplemented_returnsUnavailable() {
        IntradayVwapService.VwapResult result = service.computeFromBars("2454");
        assertThat(result.available()).isFalse();
        assertThat(result.reason()).isEqualTo("NO_INTRADAY_BAR_SOURCE");
    }
}
