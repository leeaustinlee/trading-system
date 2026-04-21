package com.austin.trading.dto.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Output of {@code BenchmarkAnalyticsEngine}.
 *
 * <p>Verdicts: {@code OUTPERFORM} / {@code MATCH} / {@code UNDERPERFORM}</p>
 */
public record BenchmarkReport(
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal strategyAvgReturn,
        BigDecimal marketAvgGain,
        BigDecimal tradedThemeAvgGain,
        BigDecimal marketAlpha,
        BigDecimal themeAlpha,
        String marketVerdict,
        String themeVerdict,
        int tradeCount,
        String payloadJson
) {}
