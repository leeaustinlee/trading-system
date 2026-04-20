package com.austin.trading.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PositionCreateRequest(
        @NotBlank String symbol,
        String stockName,
        @NotBlank String side,
        @NotNull @DecimalMin("0.0001") BigDecimal qty,
        @NotNull @DecimalMin("0.0001") BigDecimal avgCost,
        BigDecimal stopLossPrice,
        BigDecimal takeProfit1,
        BigDecimal takeProfit2,
        LocalDateTime openedAt,
        String note,
        String payloadJson,
        /** v2.3：SETUP / MOMENTUM_CHASE；null 視同 SETUP */
        String strategyType
) {
    /** 向下相容 ctor：舊呼叫不傳 strategyType，預設 SETUP。 */
    public PositionCreateRequest(String symbol, String stockName, String side,
                                  BigDecimal qty, BigDecimal avgCost,
                                  BigDecimal stopLossPrice, BigDecimal takeProfit1, BigDecimal takeProfit2,
                                  LocalDateTime openedAt, String note, String payloadJson) {
        this(symbol, stockName, side, qty, avgCost,
                stopLossPrice, takeProfit1, takeProfit2,
                openedAt, note, payloadJson, "SETUP");
    }
}
