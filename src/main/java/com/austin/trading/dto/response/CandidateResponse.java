package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CandidateResponse(
        LocalDate tradingDate,
        String symbol,
        String stockName,
        BigDecimal score,
        String reason,
        String valuationMode,
        String entryPriceZone,
        BigDecimal riskRewardRatio,
        Boolean includeInFinalPlan,
        BigDecimal stopLossPrice,
        BigDecimal takeProfit1,
        BigDecimal takeProfit2,
        // ── Phase 1 新增欄位 ────────────────────────────────
        String themeTag,
        String sector,
        // ── MVP-4 Theme-first / role-aware shadow integration ─────────────
        String candidateRole,
        BigDecimal themeImportanceScore,
        BigDecimal tradableScore,
        BigDecimal shadowRankScore,
        String themeLeaderSymbol,
        Boolean isThemeLeader,
        Boolean leaderTradable,
        String leaderRetentionReason,
        String themeTraceId,
        Integer themeRank,
        BigDecimal finalThemeScore,
        BigDecimal marketBehaviorScore,
        BigDecimal themeHeatScore,
        BigDecimal themeContinuationScore,
        BigDecimal javaStructureScore,
        BigDecimal claudeScore,
        BigDecimal codexScore,
        BigDecimal finalRankScore,
        Boolean isVetoed,
        // ── BC Sniper v2.0 新增欄位 ──────────────────────────
        BigDecimal aiWeightedScore,
        BigDecimal consensusScore,
        BigDecimal disagreementPenalty
) {
    public CandidateResponse(
            LocalDate tradingDate,
            String symbol,
            String stockName,
            BigDecimal score,
            String reason,
            String valuationMode,
            String entryPriceZone,
            BigDecimal riskRewardRatio,
            Boolean includeInFinalPlan,
            BigDecimal stopLossPrice,
            BigDecimal takeProfit1,
            BigDecimal takeProfit2,
            String themeTag,
            String sector,
            BigDecimal javaStructureScore,
            BigDecimal claudeScore,
            BigDecimal codexScore,
            BigDecimal finalRankScore,
            Boolean isVetoed,
            BigDecimal aiWeightedScore,
            BigDecimal consensusScore,
            BigDecimal disagreementPenalty
    ) {
        this(tradingDate, symbol, stockName, score, reason, valuationMode, entryPriceZone,
                riskRewardRatio, includeInFinalPlan, stopLossPrice, takeProfit1, takeProfit2,
                themeTag, sector,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null,
                javaStructureScore, claudeScore, codexScore, finalRankScore, isVetoed,
                aiWeightedScore, consensusScore, disagreementPenalty);
    }
}
