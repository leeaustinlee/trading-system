package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyPnlCreateRequest(
        @NotNull LocalDate tradingDate,
        BigDecimal realizedPnl,
        BigDecimal unrealizedPnl,
        BigDecimal winRate,
        String payloadJson
) {
}
