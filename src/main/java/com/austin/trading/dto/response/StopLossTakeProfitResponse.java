package com.austin.trading.dto.response;

public record StopLossTakeProfitResponse(
        double stopLossPrice,
        double takeProfit1,
        double takeProfit2,
        String stopLossStyle,
        String takeProfitStyle,
        String rationale
) {
}
