package com.austin.trading.dto.internal;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Input for {@code BenchmarkAnalyticsEngine}.
 *
 * <p>Market and theme benchmarks are derived from {@code theme_snapshot.avg_gain_pct}:
 * {@code marketAvgGain} = mean across all active themes; {@code tradedThemeAvgGain} =
 * mean restricted to themes present in attribution records for the period.</p>
 */
public record BenchmarkInput(
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal strategyAvgReturn,
        BigDecimal strategyWinRate,
        int strategyTradeCount,
        BigDecimal marketAvgGain,
        BigDecimal tradedThemeAvgGain
) {}
