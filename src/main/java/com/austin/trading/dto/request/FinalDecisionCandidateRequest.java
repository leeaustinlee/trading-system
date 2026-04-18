package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record FinalDecisionCandidateRequest(
        @NotBlank String stockCode,
        @NotBlank String stockName,
        @NotBlank String valuationMode,
        @NotBlank String entryType,
        @NotNull Double riskRewardRatio,
        @NotNull Boolean includeInFinalPlan,
        @NotNull Boolean mainStream,
        @NotNull Boolean falseBreakout,
        @NotNull Boolean belowOpen,
        @NotNull Boolean belowPrevClose,
        @NotNull Boolean nearDayHigh,
        @NotNull Boolean stopLossReasonable,
        String rationale,
        String entryPriceZone,
        Double stopLossPrice,
        Double takeProfit1,
        Double takeProfit2,
        // ── 評分管線欄位（Phase 2）────────────────────────────────
        BigDecimal javaStructureScore,   // Java 結構評分（0-10）
        BigDecimal claudeScore,          // Claude 研究評分（0-10，可 null）
        BigDecimal codexScore,           // Codex 審核評分（0-10，可 null）
        BigDecimal finalRankScore,       // 最終排序分（0-10；veto=0）
        Boolean    isVetoed              // 是否已被 VetoEngine 淘汰
) {
}
