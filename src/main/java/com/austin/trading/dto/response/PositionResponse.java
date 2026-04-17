package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PositionResponse(
        Long id,
        String symbol,
        String stockName,
        String side,
        BigDecimal qty,
        BigDecimal avgCost,
        String status,
        BigDecimal stopLossPrice,
        BigDecimal takeProfit1,
        BigDecimal takeProfit2,
        LocalDateTime openedAt,
        LocalDateTime closedAt,
        BigDecimal closePrice,
        String exitReason,
        BigDecimal realizedPnl,
        String note,
        String payloadJson,
        LocalDateTime createdAt
) {}
