package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record KolShadowReportResponse(
        LocalDate tradingDate,
        int candidateCount,
        List<Item> items,
        String note
) {
    public record Item(
            String symbol,
            String stockName,
            String themeTag,
            BigDecimal baseScore,
            BigDecimal kolBoostShadow,
            BigDecimal shadowScore,
            String crowdingRisk,
            NarrativeContext narrativeContext,
            String note
    ) {
    }

    public record NarrativeContext(
            boolean weakSignalOnly,
            String theme,
            String lifecycle,
            BigDecimal attention,
            String freshness,
            BigDecimal crowding,
            String direction,
            int sourceCount,
            int evidenceCount,
            BigDecimal shadowBoost,
            String guardrail
    ) {
    }
}
