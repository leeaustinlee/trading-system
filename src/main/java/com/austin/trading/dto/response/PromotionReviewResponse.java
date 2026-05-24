package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record PromotionReviewResponse(
        LocalDate tradingDate,
        boolean reviewOnly,
        boolean doesNotAffectFinalDecision,
        boolean doesNotAffectBuySellEnter,
        boolean doesNotWriteCandidateStock,
        boolean doesNotWriteProductionScore,
        boolean candidatePoolShadowIsNotTradable,
        boolean noAutoPromotion,
        boolean promotionRequiresSeparateRiskGate,
        SafetyBoundary safetyBoundary,
        int itemCount,
        List<Item> items,
        int preservedManualCount,
        int mergedManualCount,
        int deletedSystemCount,
        boolean manualReviewPreserved
) {
    public static SafetyBoundary defaultSafetyBoundary() {
        return new SafetyBoundary(true, true, true, true, true, true, true, true);
    }

    public static PromotionReviewResponse of(LocalDate date, List<Item> items) {
        return of(date, items, 0, 0, 0);
    }

    public static PromotionReviewResponse of(LocalDate date, List<Item> items,
                                             int preservedManualCount,
                                             int mergedManualCount,
                                             int deletedSystemCount) {
        return new PromotionReviewResponse(date, true, true, true, true, true, true, true, true,
                defaultSafetyBoundary(), items.size(), items,
                preservedManualCount, mergedManualCount, deletedSystemCount, true);
    }

    public record SafetyBoundary(
            boolean reviewOnly,
            boolean doesNotAffectFinalDecision,
            boolean doesNotAffectBuySellEnter,
            boolean doesNotWriteCandidateStock,
            boolean doesNotWriteProductionScore,
            boolean candidatePoolShadowIsNotTradable,
            boolean noAutoPromotion,
            boolean promotionRequiresSeparateRiskGate
    ) {}

    public record Item(
            Long id,
            LocalDate tradingDate,
            String symbol,
            String stockName,
            String themeTag,
            String source,
            String researchRole,
            String currentStatus,
            String previousStatus,
            String reviewReason,
            BigDecimal evidenceScore,
            boolean riskBlocker,
            boolean governanceBlocker,
            String lifecycleStage,
            BigDecimal themeImportanceScore,
            BigDecimal tradableScore,
            BigDecimal radarScore,
            BigDecimal replayMetricScore,
            String explainMissReason,
            String reviewer,
            LocalDateTime reviewedAt,
            String decisionReason,
            String suggestedStatus,
            boolean tradable,
            boolean notFinalDecisionEligible,
            SafetyBoundary safetyBoundary,
            Map<String, Object> evidence,
            String payloadJson
    ) {}

    public record AuditItem(
            Long id,
            Long reviewItemId,
            LocalDate tradingDate,
            String symbol,
            String fromStatus,
            String toStatus,
            String action,
            String actor,
            String reason,
            String payloadJson,
            LocalDateTime createdAt,
            SafetyBoundary safetyBoundary
    ) {}

    public record AuditResponse(
            LocalDate tradingDate,
            String symbol,
            boolean reviewOnly,
            boolean doesNotAffectFinalDecision,
            boolean doesNotAffectBuySellEnter,
            boolean doesNotWriteCandidateStock,
            boolean doesNotWriteProductionScore,
            boolean candidatePoolShadowIsNotTradable,
            boolean noAutoPromotion,
            boolean promotionRequiresSeparateRiskGate,
            SafetyBoundary safetyBoundary,
            List<AuditItem> audits
    ) {
        public static AuditResponse of(LocalDate date, String symbol, List<AuditItem> audits) {
            return new AuditResponse(date, symbol, true, true, true, true, true, true, true, true,
                    PromotionReviewResponse.defaultSafetyBoundary(), audits);
        }
    }
}
