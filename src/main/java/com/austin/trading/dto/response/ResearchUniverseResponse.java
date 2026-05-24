package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ResearchUniverseResponse(
        LocalDate tradingDate,
        boolean shadowOnly,
        boolean researchOnly,
        SafetyBoundary safetyBoundary,
        ThemeReplayMetricsResponse.MetricsSummary metrics,
        List<Item> items
) {
    public record SafetyBoundary(
            boolean shadowOnly,
            boolean researchOnly,
            boolean doesNotAffectFinalDecision,
            boolean doesNotAffectBuySellEnter,
            boolean researchUniverseNotTradable,
            boolean promotionReviewRequired
    ) {
        public static SafetyBoundary researchOnlyBoundary() {
            return new SafetyBoundary(true, true, true, true, true, true);
        }
    }

    public record Item(
            String symbol,
            String stockName,
            String themeTag,
            String researchRole,
            String source,
            BigDecimal researchScore,
            BigDecimal themeImportanceScore,
            BigDecimal tradableScore,
            BigDecimal narrativeDensityScore,
            String lifecycleStage,
            BigDecimal lifecycleScore,
            String lifecycleAdvisory,
            String governanceStatus,
            boolean researchUniverse,
            boolean tradableUniverse,
            boolean promotedToTradable,
            String promotionReason,
            String blockedReason,
            String candidateRole,
            String themeLeaderSymbol,
            boolean leadershipOnly,
            boolean leaderTradable,
            String safetyNote,
            String payloadJson,
            SafetyBoundary safetyBoundary
    ) {}

    public record GovernanceSummary(
            LocalDate tradingDate,
            long totalCount,
            long researchUniverseCount,
            long tradableUniverseCount,
            long promotedToTradableCount,
            Map<String, Long> governanceStatusCounts,
            Map<String, Long> researchRoleCounts,
            SafetyBoundary safetyBoundary,
            boolean shadowOnly,
            boolean researchOnly
    ) {}
}
