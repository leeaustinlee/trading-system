package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PromotionValidationReportResponse(
        LocalDate startDate,
        LocalDate endDate,
        String status,
        boolean validationOnly,
        boolean reviewOnly,
        boolean doesNotAffectFinalDecision,
        boolean doesNotAffectBuySellEnter,
        boolean doesNotWriteCandidateStock,
        boolean doesNotWriteProductionScore,
        boolean noAutoPromotion,
        boolean softBoostShadowOnly,
        GraduationCriteria graduationCriteria,
        Summary summary,
        List<Item> items
) {
    public static PromotionValidationReportResponse of(LocalDate startDate, LocalDate endDate, String status,
                                                       GraduationCriteria graduationCriteria, Summary summary,
                                                       List<Item> items) {
        return new PromotionValidationReportResponse(startDate, endDate, status,
                true, true, true, true, true, true, true, true, graduationCriteria, summary, items);
    }

    public record GraduationCriteria(
            int minSample,
            BigDecimal minWinRateT5,
            BigDecimal minAvgT5,
            BigDecimal maxHitStopRate,
            BigDecimal minAvgMaxDrawdown
    ) {}

    public record Summary(
            int itemCount,
            int evidenceReadyCount,
            int dataGapCount,
            int riskBlockedCount,
            int governanceBlockedCount,
            BigDecimal avgT5,
            BigDecimal winRateT5,
            BigDecimal hitStopRate,
            BigDecimal avgMaxDrawdown,
            String overallStatus,
            String overallReason
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
            String validationReason,
            BigDecimal t1ReturnPct,
            BigDecimal t5ReturnPct,
            BigDecimal t10ReturnPct,
            BigDecimal maxDrawdownPct,
            Boolean hitStop,
            String dataGapReason
    ) {}
}
