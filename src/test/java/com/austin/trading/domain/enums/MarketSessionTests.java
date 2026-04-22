package com.austin.trading.domain.enums;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v2.7 MarketSession enum 單元測試：三階段切點與能力 flag。
 */
class MarketSessionTests {

    // ── fromTime 切點 ──────────────────────────────────────────────────────

    @Test
    void beforeNineAm_isPremarket() {
        assertThat(MarketSession.fromTime(LocalTime.of(8, 30))).isEqualTo(MarketSession.PREMARKET);
        assertThat(MarketSession.fromTime(LocalTime.of(8, 59, 59))).isEqualTo(MarketSession.PREMARKET);
    }

    @Test
    void atNineAm_isOpenValidation() {
        // 09:00:00 → OPEN_VALIDATION（boundary: isBefore OPEN_VALIDATION_START 為 false）
        assertThat(MarketSession.fromTime(LocalTime.of(9, 0))).isEqualTo(MarketSession.OPEN_VALIDATION);
    }

    @Test
    void between9And930_isOpenValidation() {
        assertThat(MarketSession.fromTime(LocalTime.of(9, 15))).isEqualTo(MarketSession.OPEN_VALIDATION);
        assertThat(MarketSession.fromTime(LocalTime.of(9, 29, 59))).isEqualTo(MarketSession.OPEN_VALIDATION);
    }

    @Test
    void at930_isLiveTrading() {
        // 09:30:00 → LIVE_TRADING（boundary: isBefore LIVE_TRADING_START 為 false）
        assertThat(MarketSession.fromTime(LocalTime.of(9, 30))).isEqualTo(MarketSession.LIVE_TRADING);
    }

    @Test
    void afterMarketClose_isLiveTrading() {
        // 13:30 收盤後目前仍歸 LIVE_TRADING（session 僅管「是否禁止 final decision」）
        assertThat(MarketSession.fromTime(LocalTime.of(15, 30))).isEqualTo(MarketSession.LIVE_TRADING);
    }

    @Test
    void nullTime_fallbackToLiveTrading() {
        // 保守 fallback：null 不應讓系統停擺
        assertThat(MarketSession.fromTime(null)).isEqualTo(MarketSession.LIVE_TRADING);
    }

    // ── 能力 flag ──────────────────────────────────────────────────────────

    @Test
    void allowsFinalDecision_onlyInLiveTrading() {
        assertThat(MarketSession.PREMARKET.allowsFinalDecision()).isFalse();
        assertThat(MarketSession.OPEN_VALIDATION.allowsFinalDecision()).isFalse();
        assertThat(MarketSession.LIVE_TRADING.allowsFinalDecision()).isTrue();
    }

    @Test
    void allowsScoringUpdate_blockedInPremarketOnly() {
        assertThat(MarketSession.PREMARKET.allowsScoringUpdate()).isFalse();
        assertThat(MarketSession.OPEN_VALIDATION.allowsScoringUpdate()).isTrue();
        assertThat(MarketSession.LIVE_TRADING.allowsScoringUpdate()).isTrue();
    }

    @Test
    void allowsPriceGateHardBlock_onlyInLiveTrading() {
        assertThat(MarketSession.PREMARKET.allowsPriceGateHardBlock()).isFalse();
        assertThat(MarketSession.OPEN_VALIDATION.allowsPriceGateHardBlock()).isFalse();
        assertThat(MarketSession.LIVE_TRADING.allowsPriceGateHardBlock()).isTrue();
    }
}
