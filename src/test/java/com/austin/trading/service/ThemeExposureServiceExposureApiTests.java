package com.austin.trading.service;

import com.austin.trading.dto.response.ThemeExposureItem;
import com.austin.trading.dto.response.ThemeExposureResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v2.16 Batch C：ThemeExposureService.buildResponseFromAggregates 純資料驗算（不需要 Spring）。
 */
class ThemeExposureServiceExposureApiTests {

    private static final BigDecimal LIMIT = new BigDecimal("30");
    private static final BigDecimal WARN  = new BigDecimal("20");
    private static final BigDecimal EQUITY = new BigDecimal("100000");

    @Test
    void singleThemeOverLimit_marksOverLimit() {
        // SEMI 50%、PCB 50% → 都 >= 30 → 都 OVER_LIMIT
        var aggs = List.of(
                new ThemeExposureService.ThemeAggregate("SEMI", new BigDecimal("50000"), new BigDecimal("50000"), 1),
                new ThemeExposureService.ThemeAggregate("PCB",  new BigDecimal("50000"), new BigDecimal("50000"), 1)
        );
        ThemeExposureResponse r = ThemeExposureService.buildResponseFromAggregates(aggs, LIMIT, WARN, EQUITY);
        assertThat(r.exposures()).hasSize(2);
        assertThat(r.exposures()).allMatch(i -> "OVER_LIMIT".equals(i.status()));
        assertThat(r.totalCost()).isEqualByComparingTo("100000");
    }

    @Test
    void warnRange_marksWarn() {
        // SEMI 25%、其他 75% 拆很多小桶
        var aggs = List.of(
                new ThemeExposureService.ThemeAggregate("SEMI",   new BigDecimal("25000"), null, 1),
                new ThemeExposureService.ThemeAggregate("OTHER1", new BigDecimal("19000"), null, 1),
                new ThemeExposureService.ThemeAggregate("OTHER2", new BigDecimal("18000"), null, 1),
                new ThemeExposureService.ThemeAggregate("OTHER3", new BigDecimal("18000"), null, 1),
                new ThemeExposureService.ThemeAggregate("OTHER4", new BigDecimal("20000"), null, 1)
        );
        ThemeExposureResponse r = ThemeExposureService.buildResponseFromAggregates(aggs, LIMIT, WARN, EQUITY);
        // SEMI 25% → WARN（>=20% < 30%）；OTHER4 20% → WARN；OTHER1 19% → OK
        ThemeExposureItem semi = r.exposures().stream()
                .filter(i -> "SEMI".equals(i.theme())).findFirst().orElseThrow();
        assertThat(semi.status()).isEqualTo("WARN");
        ThemeExposureItem o4 = r.exposures().stream()
                .filter(i -> "OTHER4".equals(i.theme())).findFirst().orElseThrow();
        assertThat(o4.status()).isEqualTo("WARN");
        ThemeExposureItem o1 = r.exposures().stream()
                .filter(i -> "OTHER1".equals(i.theme())).findFirst().orElseThrow();
        assertThat(o1.status()).isEqualTo("OK");
    }

    @Test
    void emptyAggregates_returnsEmptyList() {
        ThemeExposureResponse r = ThemeExposureService.buildResponseFromAggregates(
                List.of(), LIMIT, WARN, EQUITY);
        assertThat(r.exposures()).isEmpty();
        assertThat(r.totalCost()).isEqualByComparingTo("0");
        assertThat(r.limitPct()).isEqualByComparingTo("30");
        assertThat(r.warnPct()).isEqualByComparingTo("20");
        assertThat(r.totalEquity()).isEqualByComparingTo("100000");
    }

    @Test
    void sortsByExposureDescending() {
        var aggs = List.of(
                new ThemeExposureService.ThemeAggregate("A", new BigDecimal("10000"), null, 1),
                new ThemeExposureService.ThemeAggregate("B", new BigDecimal("60000"), null, 2),
                new ThemeExposureService.ThemeAggregate("C", new BigDecimal("30000"), null, 1)
        );
        ThemeExposureResponse r = ThemeExposureService.buildResponseFromAggregates(aggs, LIMIT, WARN, EQUITY);
        assertThat(r.exposures()).hasSize(3);
        // 最大 B(60%) → C(30%) → A(10%)
        assertThat(r.exposures().get(0).theme()).isEqualTo("B");
        assertThat(r.exposures().get(0).status()).isEqualTo("OVER_LIMIT");
        assertThat(r.exposures().get(1).theme()).isEqualTo("C");
        assertThat(r.exposures().get(1).status()).isEqualTo("OVER_LIMIT");
        assertThat(r.exposures().get(2).theme()).isEqualTo("A");
        assertThat(r.exposures().get(2).status()).isEqualTo("OK");
    }
}
