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
        LocalDateTime createdAt,
        /** v2.3：SETUP / MOMENTUM_CHASE */
        String strategyType
) {
    /** 向下相容 ctor */
    public PositionResponse(Long id, String symbol, String stockName, String side,
                             BigDecimal qty, BigDecimal avgCost, String status,
                             BigDecimal stopLossPrice, BigDecimal takeProfit1, BigDecimal takeProfit2,
                             LocalDateTime openedAt, LocalDateTime closedAt,
                             BigDecimal closePrice, String exitReason, BigDecimal realizedPnl,
                             String note, String payloadJson, LocalDateTime createdAt) {
        this(id, symbol, stockName, side, qty, avgCost, status,
                stopLossPrice, takeProfit1, takeProfit2,
                openedAt, closedAt, closePrice, exitReason, realizedPnl,
                note, payloadJson, createdAt, "SETUP");
    }
}
