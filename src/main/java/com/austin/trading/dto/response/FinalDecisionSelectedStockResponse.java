package com.austin.trading.dto.response;

public record FinalDecisionSelectedStockResponse(
        String stockCode,
        String stockName,
        String entryType,
        String entryPriceZone,
        Double stopLossPrice,
        Double takeProfit1,
        Double takeProfit2,
        Double riskRewardRatio,
        String rationale,
        Double suggestedPositionSize,
        Double positionMultiplier
) {
}
