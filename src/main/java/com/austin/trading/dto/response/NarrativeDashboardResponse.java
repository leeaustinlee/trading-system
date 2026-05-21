package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record NarrativeDashboardResponse(
        LocalDate tradingDate,
        boolean weakSignalOnly,
        String guardrail,
        List<Row> rows
) {
    public record Row(
            String theme,
            String lifecycle,
            BigDecimal attention,
            BigDecimal crowding,
            String direction,
            int sourceCount,
            int evidenceCount,
            BigDecimal shadowBoost
    ) {}
}
