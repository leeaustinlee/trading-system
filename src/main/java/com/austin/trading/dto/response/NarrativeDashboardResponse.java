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
        long narrativeWarningCount
) {
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
