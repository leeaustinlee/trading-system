package com.austin.trading.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Fixed LIVE_TRADING-session clock for tests that assert FinalDecision ENTER/REST semantics. */
@TestConfiguration
public class FixedLiveTradingClockTestConfig {

    @Bean
    @Primary
    public Clock fixedLiveTradingMarketClock() {
        ZoneId zone = ZoneId.of("Asia/Taipei");
        return Clock.fixed(ZonedDateTime.of(LocalDate.of(2026, 1, 1), LocalTime.of(10, 0), zone).toInstant(), zone);
    }
}
