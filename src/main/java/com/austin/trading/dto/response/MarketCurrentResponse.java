package com.austin.trading.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MarketCurrentResponse(
        Long id,
        LocalDate tradingDate,
        String marketGrade,
        String marketPhase,
        String decision,
        String payloadJson,
        LocalDateTime createdAt
) {
}
