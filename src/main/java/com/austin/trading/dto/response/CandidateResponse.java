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
        BigDecimal javaStructureScore,
        BigDecimal claudeScore,
        BigDecimal codexScore,
        BigDecimal finalRankScore,
        Boolean isVetoed
) {
}
