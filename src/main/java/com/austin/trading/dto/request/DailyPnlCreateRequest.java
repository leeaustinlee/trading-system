package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyPnlCreateRequest(
        @NotNull LocalDate tradingDate,
        BigDecimal grossPnl,
        BigDecimal estimatedFeeAndTax,
        BigDecimal netPnl,
        Integer tradeCount,
        Integer winCount,
        Integer lossCount,
        String notes,
        // 舊欄位保留相容
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal winRate,
        String payloadJson
) {
}
