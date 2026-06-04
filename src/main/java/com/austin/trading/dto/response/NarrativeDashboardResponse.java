package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record NarrativeDashboardResponse(
        LocalDate tradingDate,
        boolean weakSignalOnly,
        String guardrail,
        List<Row> rows,
        Map<String, Long> lifecycleDistribution,
        List<String> crowdedThemes,
        List<String> emergingThemes,
        List<String> hottestThemes,
        long narrativeWarningCount,
        LocalDate latestSignalDate,
        long staleDays,
        String dataFreshnessStatus,
        LocalDate latestDataDate,
        long signalCountToday,
        long signalCount7d,
        String warning
) {
    public NarrativeDashboardResponse(LocalDate tradingDate,
                                      boolean weakSignalOnly,
                                      String guardrail,
                                      List<Row> rows,
                                      Map<String, Long> lifecycleDistribution,
                                      List<String> crowdedThemes,
                                      List<String> emergingThemes,
                                      List<String> hottestThemes,
                                      long narrativeWarningCount) {
        this(tradingDate, weakSignalOnly, guardrail, rows, lifecycleDistribution, crowdedThemes,
                emergingThemes, hottestThemes, narrativeWarningCount,
                null, 0, rows == null || rows.isEmpty() ? "EMPTY" : "LIVE", null, 0, 0,
                rows == null || rows.isEmpty() ? "NO_RECENT_SIGNAL" : null);
    }

    public record Row(
            String theme,
            String lifecycle,
            BigDecimal attention,
            String freshness,
            BigDecimal crowding,
            String direction,
            int sourceCount,
            int evidenceCount,
            BigDecimal shadowBoost
    ) {}
}
