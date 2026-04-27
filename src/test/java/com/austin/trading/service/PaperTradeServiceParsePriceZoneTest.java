package com.austin.trading.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PaperTradeServiceParsePriceZoneTest {

    @Test
    void singlePrice_returnsAsIs() {
        BigDecimal v = PaperTradeService.parsePriceZone("100");
        assertThat(v).isEqualByComparingTo("100");
    }

    @Test
    void zoneWithTilde_returnsMidpoint() {
        BigDecimal v = PaperTradeService.parsePriceZone("100~105");
        assertThat(v).isEqualByComparingTo("102.5");
    }

    @Test
    void zoneWithHyphen_returnsMidpoint() {
        BigDecimal v = PaperTradeService.parsePriceZone("100-110");
        assertThat(v).isEqualByComparingTo("105");
    }

    @Test
    void chineseSeparator_returnsMidpoint() {
        BigDecimal v = PaperTradeService.parsePriceZone("100元至110元");
        assertThat(v).isEqualByComparingTo("105");
    }

    @Test
    void decimalsAreSupported() {
        BigDecimal v = PaperTradeService.parsePriceZone("70.5~71.2");
        assertThat(v).isEqualByComparingTo("70.85");
    }

    @Test
    void blankOrNull_returnsNull() {
        assertThat(PaperTradeService.parsePriceZone("")).isNull();
        assertThat(PaperTradeService.parsePriceZone(null)).isNull();
        assertThat(PaperTradeService.parsePriceZone("   ")).isNull();
    }

    @Test
    void noNumber_returnsNull() {
        assertThat(PaperTradeService.parsePriceZone("待回測")).isNull();
    }
}
