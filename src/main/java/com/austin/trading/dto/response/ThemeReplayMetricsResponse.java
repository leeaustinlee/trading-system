package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ThemeReplayMetricsResponse(
        LocalDate tradingDate,
        boolean replayOnly,
        boolean analyticsOnly,
        SafetyBoundary safetyBoundary,
        List<Item> items
) {
    public record Item(
            LocalDate tradingDate,
            String themeTag,
            BigDecimal leaderRetentionRate,
            BigDecimal peerDiscoveryHitRate,
            int taxonomyGapDiscoveryCount,
            BigDecimal researchUniverseCoverage,
            int candidateDiversification,
            int riskRejectedLeaderCount,
            int falsePromotionCount,
            int chaseHighAvoidedCount,
            int riskGateBypassCount,
            int leadershipOnlyEnteredCount,
            int leaderTradableFalseEnterCount,
            int peerShadowDirectPromotionCount,
            int narrativeDirectEnterCount,
            int researchVsTradableSeparationViolationCount,
            BigDecimal postSignalReturn1d,
            BigDecimal postSignalReturn3d,
            BigDecimal postSignalReturn5d,
            BigDecimal maxDrawdownAfterSignal,
            BigDecimal pullbackEntryReturn,
            BigDecimal breakoutEntryReturn,
            BigDecimal lowBaseFollowerReturn,
            BigDecimal stageTransitionAccuracy,
            BigDecimal emergingToMainstreamHitRate,
            BigDecimal overheatedAvoidanceReturn,
            BigDecimal distributionWarningLeadTime,
            BigDecimal deadThemeFalsePositiveRate,
            BigDecimal aiGovernanceAnnotatedRate,
            BigDecimal rejectionReasonCoverage,
            BigDecimal finalDecisionTraceCoverage,
            String payloadJson,
            SafetyBoundary safetyBoundary
    ) {}

    public record MetricsSummary(
            BigDecimal leaderRetentionRate,
            BigDecimal peerDiscoveryHitRate,
            int candidateDiversification,
            int riskGateBypassCount,
            int leadershipOnlyEnteredCount,
            int leaderTradableFalseEnterCount,
            int researchVsTradableSeparationViolationCount
    ) {
        public static MetricsSummary empty() {
            return new MetricsSummary(BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, 0);
        }
    }

    public record SafetySummary(
            LocalDate tradingDate,
            int themeCount,
            int riskGateBypassCount,
            int leadershipOnlyEnteredCount,
            int leaderTradableFalseEnterCount,
            int peerShadowDirectPromotionCount,
            int narrativeDirectEnterCount,
            int researchVsTradableSeparationViolationCount,
            boolean safetyViolationDetected,
            SafetyBoundary safetyBoundary,
            boolean replayOnly,
            boolean analyticsOnly,
            boolean noAutoPromotion
    ) {}

    public record BuildResult(
            LocalDate tradingDate,
            int builtCount,
            boolean replayOnly,
            boolean analyticsOnly,
            boolean noAutoPromotion,
            SafetyBoundary safetyBoundary,
            Map<String, MetricsSummary> metrics,
            List<Item> items
    ) {}

    public record SafetyBoundary(
            boolean replayOnly,
            boolean analyticsOnly,
            boolean doesNotAffectFinalDecision,
            boolean doesNotAffectBuySellEnter,
            boolean metricsDoNotOverrideRiskGate,
            boolean noAutoPromotion,
            boolean doesNotWriteCandidateStock,
            boolean doesNotWriteProductionScore
    ) {
        public static SafetyBoundary replayAnalyticsOnlyBoundary() {
            return new SafetyBoundary(true, true, true, true, true, true, true, true);
        }
    }
}
