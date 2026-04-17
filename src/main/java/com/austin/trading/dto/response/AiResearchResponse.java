package com.austin.trading.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record AiResearchResponse(
        Long id,
        LocalDate tradingDate,
        String researchType,
        String symbol,
        String promptSummary,
        String researchResult,
        String model,
        Integer tokensUsed,
        LocalDateTime createdAt
) {
}
