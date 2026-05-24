package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record ThemeLifecycleResponse(
        LocalDate tradingDate,
        boolean replayOnly,
        boolean advisoryOnly,
        SafetyBoundary safetyBoundary,
        List<Item> items
) {
    public record Item(
            LocalDate tradingDate,
            String themeTag,
            String stage,
            String previousStage,
            boolean stageChanged,
            BigDecimal stageConfidence,
            int leaderCount,
            int breadth,
            BigDecimal volumeExpansion,
            int continuationDays,
            BigDecimal rotationScore,
            BigDecimal crowdingScore,
            BigDecimal limitUpDensity,
            BigDecimal narrativeDensity,
            BigDecimal institutionalFlowScore,
            BigDecimal lifecycleScore,
            String reason,
            List<String> recommendedPlaybook,
            List<String> avoidPlaybook,
            String payloadJson,
            SafetyBoundary safetyBoundary
    ) {}

    public record SafetyBoundary(
            boolean replayOnly,
            boolean advisoryOnly,
            boolean doesNotAffectFinalDecision,
            boolean doesNotAffectBuySellEnter,
            boolean lifecycleDoesNotOverrideRiskGate,
            boolean doesNotWriteCandidateStock,
            boolean doesNotWriteProductionScore,
            boolean doesNotPromoteResearchUniverse
    ) {
        public static SafetyBoundary lifecycleReplayOnlyBoundary() {
            return new SafetyBoundary(true, true, true, true, true, true, true, true);
        }
    }

    public record BuildResult(
            LocalDate tradingDate,
            int builtCount,
            boolean replayOnly,
            boolean advisoryOnly,
            SafetyBoundary safetyBoundary,
            Map<String, String> stages,
            List<Item> items
    ) {}
}
