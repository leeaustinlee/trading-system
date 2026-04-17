package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotNull;

public record StopLossTakeProfitEvaluateRequest(
        @NotNull Double entryPrice,
        @NotNull Double stopLossPercent,
        @NotNull Double takeProfit1Percent,
        @NotNull Double takeProfit2Percent,
        @NotNull Boolean volatileStock
) {
}
