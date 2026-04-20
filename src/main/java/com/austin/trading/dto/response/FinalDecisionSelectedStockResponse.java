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
        Double positionMultiplier,
        /** v2.3：SETUP / MOMENTUM_CHASE */
        String strategyType,
        /** v2.3：Momentum 總分 0-10；SETUP 時 null */
        Double momentumScore
) {
    /** 向下相容 ctor（舊呼叫預設 SETUP、無 momentumScore）。 */
    public FinalDecisionSelectedStockResponse(
            String stockCode, String stockName, String entryType, String entryPriceZone,
            Double stopLossPrice, Double takeProfit1, Double takeProfit2,
            Double riskRewardRatio, String rationale,
            Double suggestedPositionSize, Double positionMultiplier) {
        this(stockCode, stockName, entryType, entryPriceZone,
                stopLossPrice, takeProfit1, takeProfit2,
                riskRewardRatio, rationale,
                suggestedPositionSize, positionMultiplier,
                "SETUP", null);
    }
}
