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
        Boolean    isVetoed,             // 是否已被 VetoEngine 淘汰
        // ── Java 結構評分輸入（Phase 2 補充）────────────────────
        BigDecimal baseScore,            // 候選股原始分（CandidateStockEntity.score）
        Boolean    hasTheme,             // 是否有題材標籤
        // ── BC Sniper v2.0 新增欄位 ──────────────────────────
        Integer    themeRank,            // 題材排名（ThemeSnapshot.rankingOrder）
        BigDecimal finalThemeScore,      // 題材最終分（ThemeSnapshot.finalThemeScore）
        BigDecimal consensusScore,       // 共識分（ConsensusScoringEngine 計算）
        BigDecimal disagreementPenalty,  // 分歧懲罰（ConsensusScoringEngine 計算）
        Boolean    volumeSpike,          // 爆量但未突破
        Boolean    priceNotBreakHigh,    // 價格未突破近期高點
        Boolean    entryTooExtended,     // 進場位置距突破點太遠
        Boolean    entryTriggered        // 是否已觸發明確進場訊號（突破/回測確認）
) {
}
