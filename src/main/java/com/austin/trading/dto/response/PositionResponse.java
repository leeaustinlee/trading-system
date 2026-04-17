package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PositionResponse(
        Long id,
        String symbol,
        String side,
        BigDecimal qty,
        BigDecimal avgCost,
        String status,
        LocalDateTime openedAt,
        LocalDateTime closedAt,
        BigDecimal closePrice,
        BigDecimal realizedPnl,
        String payloadJson,
        LocalDateTime createdAt
) {
}
