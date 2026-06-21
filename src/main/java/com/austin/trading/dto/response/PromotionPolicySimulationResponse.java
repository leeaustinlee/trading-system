package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PromotionPolicySimulationResponse(
        LocalDate startDate,
        LocalDate endDate,
        String status,
        boolean simulationOnly,
        boolean reviewOnly,
        boolean doesNotAffectFinalDecision,
        boolean doesNotAffectBuySellEnter,
        boolean doesNotWriteCandidateStock,
        boolean doesNotWriteProductionScore,
        boolean noAutoPromotion,
        boolean boundedSoftBoostShadowOnly,
        Summary summary,
        List<Item> items
) {
    public static PromotionPolicySimulationResponse of(LocalDate startDate, LocalDate endDate, String status,
                                                       Summary summary, List<Item> items) {
        return new PromotionPolicySimulationResponse(startDate, endDate, status,
                true, true, true, true, true, true, true, true, summary, items);
    }

    public record Summary(
            int itemCount,
            int matchedForwardCount,
            int dataGapCount,
            BigDecimal avgT1,
            BigDecimal avgT5,
            BigDecimal avgT10,
            BigDecimal winRateT5,
            int hitStopCount,
            BigDecimal maxDrawdownAvg,
            int blockedByRiskCount,
            int blockedByGovernanceCount
    ) {}

    public record Item(
            Long id,
            LocalDate tradingDate,
            String symbol,
            String stockName,
            String themeTag,
            String source,
            String currentStatus,
            String suggestedPolicy,
            BigDecimal t1ReturnPct,
            BigDecimal t5ReturnPct,
            BigDecimal t10ReturnPct,
            BigDecimal maxDrawdownPct,
            Boolean hitStop,
            boolean riskBlocker,
            boolean governanceBlocker,
            String dataGapReason
    ) {}
}
