package com.austin.trading.dto.response;

public record StockEvaluateResult(
        String symbol,
        String valuationMode,
        String entryPriceZone,
        Double stopLossPrice,
        Double takeProfit1,
        Double takeProfit2,
        Double riskRewardRatio,
        Boolean includeInFinalPlan,
        String rejectReason,
        String rationale
) {
}
