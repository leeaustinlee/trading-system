package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record FinalDecisionCandidateRequest(
        @NotBlank String stockCode,
        @NotBlank String stockName,
        @NotBlank String valuationMode,
        @NotBlank String entryType,
        @NotNull Double riskRewardRatio,
        @NotNull Boolean includeInFinalPlan,
        @NotNull Boolean mainStream,
        @NotNull Boolean falseBreakout,
        @NotNull Boolean belowOpen,
        @NotNull Boolean belowPrevClose,
        @NotNull Boolean nearDayHigh,
        @NotNull Boolean stopLossReasonable,
        String rationale,
        String entryPriceZone,
        Double stopLossPrice,
        Double takeProfit1,
        Double takeProfit2
) {
}
