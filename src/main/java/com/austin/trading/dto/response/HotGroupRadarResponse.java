package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record HotGroupRadarResponse(
        LocalDate tradingDate,
        String sourcePhase,
        boolean shadowOnly,
        boolean observabilityOnly,
        boolean doesNotAffectFinalDecision,
        boolean doesNotWriteCandidateStock,
        boolean doesNotWriteProductionScore,
        SafetyBoundary safetyBoundary,
        List<ThemeItem> themes,
        List<SignalItem> signals
) {
    public HotGroupRadarResponse {
        themes = themes == null ? List.of() : List.copyOf(themes);
        signals = signals == null ? List.of() : List.copyOf(signals);
    }

    public record SafetyBoundary(
            boolean shadowOnly,
            boolean observabilityOnly,
            boolean doesNotAffectFinalDecision,
            boolean doesNotAffectBuySellEnter,
            boolean doesNotWriteCandidateStock,
            boolean doesNotWriteProductionScore,
            boolean doesNotOverrideRiskGate,
            boolean noDirectBuy
    ) {
        public static SafetyBoundary shadowOnlyBoundary() {
            return new SafetyBoundary(true, true, true, true, true, true, true, true);
        }
    }

    public record ThemeItem(
            String themeTag,
            String themeCategory,
            BigDecimal hotScore,
            int leaderCount,
            int limitUpCount,
            int nearLimitCount,
            int upStockCount,
            BigDecimal avgChangePct,
            BigDecimal totalTurnoverYi,
            BigDecimal diffusionScore,
            BigDecimal newsScore,
            boolean priceHikeSignal,
            String riskLevel
    ) {}

    public record SignalItem(
            String themeTag,
            String symbol,
            String stockName,
            String role,
            BigDecimal changePct,
            BigDecimal turnoverYi,
            BigDecimal nearHigh,
            boolean limitRisk,
            BigDecimal boardLotCost,
            String tradabilityTag,
            BigDecimal radarRankScore,
            String candidateAction,
            String rejectionReason
    ) {}

    public record ExplainMiss(
            LocalDate tradingDate,
            String symbol,
            boolean inUniverse,
            boolean inHotStock,
            boolean classifiedAsOtherBeforeRadar,
            boolean limitRisk,
            boolean affordabilityFail,
            boolean finalCandidateFail,
            boolean hotGroupRadarWatchOnly,
            boolean shadowOnly,
            List<String> reasons,
            SafetyBoundary safetyBoundary
    ) {
        public ExplainMiss {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    public record CandidateFeed(
            LocalDate tradingDate,
            String sourcePhase,
            boolean shadowOnly,
            boolean observabilityOnly,
            boolean doesNotWriteCandidateStock,
            SafetyBoundary safetyBoundary,
            List<SignalItem> watchOnly,
            List<SignalItem> boostExistingCandidates,
            List<SignalItem> addToCandidatePoolShadow,
            List<SignalItem> rejectDueToRisk
    ) {
        public CandidateFeed {
            watchOnly = watchOnly == null ? List.of() : List.copyOf(watchOnly);
            boostExistingCandidates = boostExistingCandidates == null ? List.of() : List.copyOf(boostExistingCandidates);
            addToCandidatePoolShadow = addToCandidatePoolShadow == null ? List.of() : List.copyOf(addToCandidatePoolShadow);
            rejectDueToRisk = rejectDueToRisk == null ? List.of() : List.copyOf(rejectDueToRisk);
        }
    }
}
