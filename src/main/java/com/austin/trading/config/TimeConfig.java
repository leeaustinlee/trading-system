package com.austin.trading.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Central clock bean for time-sensitive trading/session logic.
 *
 * <p>Production uses the Taiwan market timezone. Tests can replace this bean with
 * a fixed {@link Clock} to avoid wall-clock dependent FinalDecision behavior.</p>
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock marketClock() {
        return Clock.system(ZoneId.of("Asia/Taipei"));
    }
}
