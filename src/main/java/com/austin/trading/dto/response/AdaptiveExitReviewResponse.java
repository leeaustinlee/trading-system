package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public record AdaptiveExitReviewResponse(
        LocalDateTime evaluatedAt,
        boolean reviewOnly,
        boolean productionDecisionAllowed,
        boolean autoBuyEnabled,
        boolean autoSellEnabled,
        boolean manualConfirmRequired,
        SafetyBoundary safetyBoundary,
        int positionCount,
        List<Item> items
) {
    public static AdaptiveExitReviewResponse of(List<Item> items) {
        return new AdaptiveExitReviewResponse(
                LocalDateTime.now(),
                true,
                false,
                false,
                false,
                true,
                SafetyBoundary.defaultBoundary(),
                items == null ? 0 : items.size(),
                items == null ? List.of() : items);
    }

    public record SafetyBoundary(
            boolean readOnly,
            boolean productionDecisionAllowed,
            boolean autoBuyEnabled,
            boolean autoSellEnabled,
            boolean manualConfirmRequired,
            boolean doesNotAffectBuyEnter,
            boolean doesNotAutoBuy,
            boolean doesNotAutoSell,
            boolean doesNotOverridePaperTradeExit,
            boolean doesNotLoosenStopLoss
    ) {
        public static SafetyBoundary defaultBoundary() {
            return new SafetyBoundary(true, false, false, false, true, true, true, true, true, true);
        }
    }

    public enum Recommendation {
        HARD_EXIT,
        EXIT_REVIEW,
        OBSERVE_1D,
        HOLD,
        REDUCE_REVIEW
    }

    public record Item(
            String symbol,
            BigDecimal currentPrice,
            BigDecimal avgCost,
            BigDecimal unrealizedPnlPct,
            Map<String, Object> originalExitSignal,
            Map<String, Object> healthV2Signal,
            Map<String, Object> stopOutcomeEvidence,
            boolean themeStillActive,
            String structureStatus,
            String thesisStatus,
            String thesisSummary,
            String invalidationCondition,
            String themeLifecycle,
            BigDecimal narrativeHeat,
            String crowdingRisk,
            String wavePhase,
            BigDecimal rotationStrength,
            String institutionalAlignment,
            String sectorLeadership,
            Recommendation recommendation,
            String themeContextStatus,
            java.time.LocalDate themeDataDate,
            long themeStaleDays,
            boolean productionDecisionAllowed,
            boolean autoBuyEnabled,
            boolean autoSellEnabled,
            boolean manualConfirmRequired,
            String safetyNote
    ) {
        public static Item create(String symbol,
                                  BigDecimal currentPrice,
                                  BigDecimal avgCost,
                                  BigDecimal unrealizedPnlPct,
                                  Map<String, Object> originalExitSignal,
                                  Map<String, Object> healthV2Signal,
                                  Map<String, Object> stopOutcomeEvidence,
                                  boolean themeStillActive,
                                  String structureStatus,
                                  String thesisStatus,
                                  String thesisSummary,
                                  String invalidationCondition,
                                  String themeLifecycle,
                                  BigDecimal narrativeHeat,
                                  String crowdingRisk,
                                  String wavePhase,
                                  BigDecimal rotationStrength,
                                  String institutionalAlignment,
                                  String sectorLeadership,
                                  Recommendation recommendation) {
            return new Item(symbol, currentPrice, avgCost, unrealizedPnlPct,
                    originalExitSignal == null ? Map.of() : originalExitSignal,
                    healthV2Signal == null ? Map.of() : healthV2Signal,
                    stopOutcomeEvidence == null ? Map.of() : stopOutcomeEvidence,
                    themeStillActive,
                    structureStatus,
                    thesisStatus == null ? "UNKNOWN" : thesisStatus,
                    thesisSummary,
                    invalidationCondition,
                    themeLifecycle == null ? "UNKNOWN" : themeLifecycle,
                    narrativeHeat,
                    crowdingRisk == null ? "UNKNOWN" : crowdingRisk,
                    wavePhase == null ? "UNKNOWN" : wavePhase,
                    rotationStrength,
                    institutionalAlignment == null ? "UNKNOWN" : institutionalAlignment,
                    sectorLeadership == null ? "UNKNOWN" : sectorLeadership,
                    recommendation,
                    themeContextStatus(themeLifecycle),
                    null,
                    0,
                    false,
                    false,
                    false,
                    true,
                    "Adaptive Exit Review is read-only/manual-confirm only; it never changes BUY/ENTER, never auto-buys, never auto-sells, never overrides PaperTrade exit, and never loosens stops.");
        }

        private static String themeContextStatus(String themeLifecycle) {
            if (themeLifecycle == null || themeLifecycle.isBlank() || "UNKNOWN".equalsIgnoreCase(themeLifecycle)) {
                return "MISSING_THEME_CONTEXT";
            }
            if (themeLifecycle.toUpperCase(java.util.Locale.ROOT).contains("STALE")) {
                return "STALE_THEME_CONTEXT";
            }
            return "LIVE_THEME_CONTEXT";
        }
    }
}
