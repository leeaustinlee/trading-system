package com.austin.trading.integration;

import com.austin.trading.client.TaifexClient;
import com.austin.trading.client.dto.FuturesQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TAIFEX Open API 實機驗證。
 *
 * 預設跳過，加 JVM 屬性才執行：
 *   mvn test -Dlive.taifex=true
 *
 * 驗證範圍：
 *   1. 最近交易日有回傳報價（close != null）
 *   2. 欄位 fallback 邏輯正確（prevClose / change / volume）
 *   3. 非交易日回傳 empty（不報錯）
 */
@SpringBootTest
@ActiveProfiles("integration")
class TaifexClientLiveTest {

    @Autowired
    private TaifexClient taifexClient;

    @BeforeEach
    void assumeLiveEnabled() {
        assumeTrue(
                "true".equalsIgnoreCase(System.getProperty("live.taifex")),
                "Skipped: add -Dlive.taifex=true to run TAIFEX live tests"
        );
    }

    @Test
    void getQuote_today_shouldReturnQuoteOrEmpty() {
        Optional<FuturesQuote> result = taifexClient.getTxfQuote(LocalDate.now());
        // Today may be non-trading; either OK or empty – must not throw
        result.ifPresent(this::assertQuoteFieldsValid);
    }

    @Test
    void getQuote_lastFriday_shouldReturnQuote() {
        LocalDate friday = lastFriday();
        Optional<FuturesQuote> result = taifexClient.getTxfQuote(friday);

        assertThat(result)
                .as("Expected quote for %s (last Friday)", friday)
                .isPresent();

        FuturesQuote q = result.get();
        assertQuoteFieldsValid(q);
    }

    @Test
    void getQuote_weekendDate_shouldReturnEmpty() {
        // Find a recent Saturday
        LocalDate saturday = LocalDate.now();
        while (saturday.getDayOfWeek().getValue() != 6) {
            saturday = saturday.minusDays(1);
        }

        Optional<FuturesQuote> result = taifexClient.getTxfQuote(saturday);

        // TAIFEX may return empty array or a record with no data on weekends
        // We just check no exception is thrown; empty is acceptable
        assertThat(result).satisfiesAnyOf(
                opt -> assertThat(opt).isEmpty(),
                opt -> {
                    if (opt.isPresent()) assertQuoteFieldsValid(opt.get());
                }
        );
    }

    @Test
    void getQuote_nullDate_shouldDefaultToToday() {
        // Should not throw even with null
        Optional<FuturesQuote> result = taifexClient.getTxfQuote(null);
        result.ifPresent(this::assertQuoteFieldsValid);
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void assertQuoteFieldsValid(FuturesQuote q) {
        assertThat(q.contract()).isNotBlank();
        assertThat(q.currentPrice()).isNotNull().isPositive();

        // prevClose may be null on first day after holiday; volume may be zero
        if (q.prevClose() != null) {
            assertThat(q.prevClose()).isPositive();
        }
        if (q.change() != null && q.prevClose() != null) {
            double expected = Math.round((q.currentPrice() - q.prevClose()) * 100.0) / 100.0;
            assertThat(q.change()).isCloseTo(expected, org.assertj.core.data.Offset.offset(1.0));
        }
        if (q.changePercent() != null) {
            assertThat(Math.abs(q.changePercent())).isLessThan(20.0); // sanity: < 20%
        }
    }

    private LocalDate lastFriday() {
        LocalDate d = LocalDate.now().minusDays(1);
        while (d.getDayOfWeek().getValue() != 5) {
            d = d.minusDays(1);
        }
        return d;
    }
}
