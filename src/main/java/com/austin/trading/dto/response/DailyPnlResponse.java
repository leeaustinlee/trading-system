package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record DailyPnlResponse(
        Long id,
        LocalDate tradingDate,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal winRate,
        String payloadJson,
        LocalDateTime createdAt
) {
}
