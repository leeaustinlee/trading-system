package com.austin.trading.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record FinalDecisionRecordResponse(
        Long id,
        LocalDate tradingDate,
        String decision,
        String summary,
        String payloadJson,
        LocalDateTime createdAt
) {
}
