package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ThemeReplayTimelineResponse(
        LocalDate tradingDate,
        String themeTag,
        String leaderSymbol,
        ThemeReplaySummaryResponse snapshot,
        List<Node> nodes,
        List<Edge> edges,
        List<Event> events,
        SafetyBoundary safetyBoundary,
        boolean shadowOnly,
        boolean replayOnly
) {
    public record Node(
            String symbol,
            String stockName,
            String researchRole,
            String candidateRole,
            boolean isThemeLeader,
            boolean leadershipOnly,
            String themeLeaderSymbol,
            boolean researchUniverse,
            boolean tradableUniverse,
            boolean leaderTradable,
            BigDecimal themeImportanceScore,
            BigDecimal tradableScore,
            BigDecimal shadowRankScore,
            BigDecimal divergenceScore,
            BigDecimal taxonomyGapScore,
            boolean riskRejected,
            String rejectionReason,
            String safetyNote,
            String aiGovernanceSummary,
            String payloadJson
    ) {}

    public record Edge(
            String fromSymbol,
            String toSymbol,
            String edgeType,
            BigDecimal confidence,
            String reason,
            String payloadJson
    ) {}

    public record Event(
            String eventType,
            String symbol,
            String message,
            LocalDateTime time
    ) {}

    public record SafetyBoundary(
            boolean shadowOnly,
            boolean replayOnly,
            boolean doesNotAffectFinalDecision,
            boolean doesNotAffectBuySellEnter,
            boolean researchUniverseNotTradable,
            boolean doesNotWriteCandidateStock,
            boolean doesNotWriteProductionScore
    ) {
        public static SafetyBoundary replayOnlyBoundary() {
            return new SafetyBoundary(true, true, true, true, true, true, true);
        }
    }
}
