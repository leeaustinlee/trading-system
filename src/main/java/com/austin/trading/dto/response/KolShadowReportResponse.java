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
            String note
    ) {
    }
}
