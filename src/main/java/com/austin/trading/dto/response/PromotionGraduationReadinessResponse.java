package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PromotionGraduationReadinessResponse(
        LocalDate startDate,
        LocalDate endDate,
        String status,
        boolean readinessReportOnly,
        boolean thresholdTuningSuggestionOnly,
        boolean reviewOnly,
        boolean doesNotAffectFinalDecision,
        boolean doesNotAffectBuySellEnter,
        boolean doesNotWriteCandidateStock,
        boolean doesNotWriteProductionScore,
        boolean noAutoPromotion,
        boolean noThresholdMutation,
        boolean softBoostShadowOnly,
        PromotionValidationReportResponse.GraduationCriteria currentCriteria,
        ReadinessSummary summary,
        List<ThresholdSuggestion> thresholdSuggestions,
        List<Item> items
) {
    public static PromotionGraduationReadinessResponse of(LocalDate startDate, LocalDate endDate, String status,
                                                          PromotionValidationReportResponse.GraduationCriteria currentCriteria,
                                                          ReadinessSummary summary,
                                                          List<ThresholdSuggestion> thresholdSuggestions,
                                                          List<Item> items) {
        return new PromotionGraduationReadinessResponse(startDate, endDate, status,
                true, true, true, true, true, true, true, true, true, true,
                currentCriteria, summary, thresholdSuggestions, items);
    }

    public record ReadinessSummary(
            String readinessStatus,
            String readinessReason,
            int sampleCount,
            int minSample,
            int sampleShortfall,
            int evidenceReadyCount,
            int dataGapCount,
            int riskBlockedCount,
            int governanceBlockedCount,
            BigDecimal avgT5,
            BigDecimal winRateT5,
            BigDecimal hitStopRate,
            BigDecimal avgMaxDrawdown,
            BigDecimal minWinRateT5,
            BigDecimal minAvgT5,
            BigDecimal maxHitStopRate,
            BigDecimal minAvgMaxDrawdown
    ) {}

    public record ThresholdSuggestion(
            String key,
            String currentValue,
            String suggestedValue,
            String direction,
            String reason,
            boolean manualReviewRequired,
            boolean appliesToShadowOnly
    ) {}

    public record Item(
            Long id,
            LocalDate tradingDate,
            String symbol,
            String stockName,
            String themeTag,
            String source,
            String currentStatus,
            String validationStatus,
            String readinessStatus,
            String readinessReason,
            BigDecimal t5ReturnPct,
            BigDecimal maxDrawdownPct,
            Boolean hitStop,
            String dataGapReason
    ) {}
}
