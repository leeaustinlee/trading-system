package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CandidateResponse(
        LocalDate tradingDate,
        String symbol,
        String stockName,
        BigDecimal score,
        String reason,
        String valuationMode,
        String entryPriceZone,
        BigDecimal riskRewardRatio,
        Boolean includeInFinalPlan
) {
}
