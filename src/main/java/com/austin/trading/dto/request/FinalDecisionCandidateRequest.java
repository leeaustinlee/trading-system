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
        Boolean    entryTriggered,       // 是否已觸發明確進場訊號（突破/回測確認）
        // ── v2.9 Price Gate Refactor（Gate 6/7）──────────────────
        BigDecimal currentPrice,         // 現價（盤中報價，可 null）
        BigDecimal openPrice,            // 開盤價（可 null）
        BigDecimal previousClose,        // 昨收價（可 null）
        BigDecimal vwapPrice,            // VWAP（可 null；TWSE MIS 目前不提供）
        BigDecimal volumeRatio,          // 當前成交量 / 平均成交量（可 null）
        BigDecimal distanceFromOpenPct,  // (currentPrice-openPrice)/openPrice（signed；負值=跌破開盤；可 null）
        BigDecimal dropFromPrevClosePct, // (previousClose-currentPrice)/previousClose（正值=跌破昨收；可 null）
        String     marketRegime,         // BULL_TREND / RANGE_CHOP / WEAK_DOWNTREND / PANIC_VOLATILITY（可 null）
        // ── v2.16 Batch C：ChasedHigh 真實 dayHigh ───────────────────
        BigDecimal dayHigh,              // 當日最高價（可 null；fallback entryPriceZone 上緣）
        // ── 2026-04-29 P0.3：tradabilityTag（PowerShell screener 自我標示） ──────
        String     tradabilityTag        // 可回測進場候選 / 漲幅過大,僅參考 / 不列主進場 / 高價研究參考,非主進場（可 null）
) {
    /**
     * Legacy 31-arg constructor（v2.8 之前無 price gate 細節欄位時使用）。
     * 新欄位預設為 null，PriceGateEvaluator 會以保守 fallback 處理。
     */
    public FinalDecisionCandidateRequest(
            String stockCode,
            String stockName,
            String valuationMode,
            String entryType,
            Double riskRewardRatio,
            Boolean includeInFinalPlan,
            Boolean mainStream,
            Boolean falseBreakout,
            Boolean belowOpen,
            Boolean belowPrevClose,
            Boolean nearDayHigh,
            Boolean stopLossReasonable,
            String rationale,
            String entryPriceZone,
            Double stopLossPrice,
            Double takeProfit1,
            Double takeProfit2,
            BigDecimal javaStructureScore,
            BigDecimal claudeScore,
            BigDecimal codexScore,
            BigDecimal finalRankScore,
            Boolean    isVetoed,
            BigDecimal baseScore,
            Boolean    hasTheme,
            Integer    themeRank,
            BigDecimal finalThemeScore,
            BigDecimal consensusScore,
            BigDecimal disagreementPenalty,
            Boolean    volumeSpike,
            Boolean    priceNotBreakHigh,
            Boolean    entryTooExtended,
            Boolean    entryTriggered
    ) {
        this(stockCode, stockName, valuationMode, entryType, riskRewardRatio,
                includeInFinalPlan, mainStream, falseBreakout, belowOpen, belowPrevClose,
                nearDayHigh, stopLossReasonable, rationale, entryPriceZone, stopLossPrice,
                takeProfit1, takeProfit2, javaStructureScore, claudeScore, codexScore,
                finalRankScore, isVetoed, baseScore, hasTheme, themeRank, finalThemeScore,
                consensusScore, disagreementPenalty, volumeSpike, priceNotBreakHigh,
                entryTooExtended, entryTriggered,
                null, null, null, null, null, null, null, null,
                null,
                null);
    }

    /**
     * v2.9 ctor（39-arg；Gate 6/7 完整 priceGate 欄位但無 dayHigh）。
     * 新欄位 dayHigh / tradabilityTag 預設 null，ChasedHigh evaluator fallback 到 entryPriceZone 上緣，
     * tradabilityTag gate 對 null tag 一律不觸發。
     */
    public FinalDecisionCandidateRequest(
            String stockCode,
            String stockName,
            String valuationMode,
            String entryType,
            Double riskRewardRatio,
            Boolean includeInFinalPlan,
            Boolean mainStream,
            Boolean falseBreakout,
            Boolean belowOpen,
            Boolean belowPrevClose,
            Boolean nearDayHigh,
            Boolean stopLossReasonable,
            String rationale,
            String entryPriceZone,
            Double stopLossPrice,
            Double takeProfit1,
            Double takeProfit2,
            BigDecimal javaStructureScore,
            BigDecimal claudeScore,
            BigDecimal codexScore,
            BigDecimal finalRankScore,
            Boolean    isVetoed,
            BigDecimal baseScore,
            Boolean    hasTheme,
            Integer    themeRank,
            BigDecimal finalThemeScore,
            BigDecimal consensusScore,
            BigDecimal disagreementPenalty,
            Boolean    volumeSpike,
            Boolean    priceNotBreakHigh,
            Boolean    entryTooExtended,
            Boolean    entryTriggered,
            BigDecimal currentPrice,
            BigDecimal openPrice,
            BigDecimal previousClose,
            BigDecimal vwapPrice,
            BigDecimal volumeRatio,
            BigDecimal distanceFromOpenPct,
            BigDecimal dropFromPrevClosePct,
            String     marketRegime
    ) {
        this(stockCode, stockName, valuationMode, entryType, riskRewardRatio,
                includeInFinalPlan, mainStream, falseBreakout, belowOpen, belowPrevClose,
                nearDayHigh, stopLossReasonable, rationale, entryPriceZone, stopLossPrice,
                takeProfit1, takeProfit2, javaStructureScore, claudeScore, codexScore,
                finalRankScore, isVetoed, baseScore, hasTheme, themeRank, finalThemeScore,
                consensusScore, disagreementPenalty, volumeSpike, priceNotBreakHigh,
                entryTooExtended, entryTriggered,
                currentPrice, openPrice, previousClose, vwapPrice, volumeRatio,
                distanceFromOpenPct, dropFromPrevClosePct, marketRegime,
                null,
                null);
    }

    /**
     * v2.16 Batch C ctor（40-arg；無 tradabilityTag）。
     * 新欄位 tradabilityTag 預設 null。所有 caller 在 P0.3 前都會走這條路徑，
     * tradabilityTag gate 對 null tag 不觸發。
     */
    public FinalDecisionCandidateRequest(
            String stockCode,
            String stockName,
            String valuationMode,
            String entryType,
            Double riskRewardRatio,
            Boolean includeInFinalPlan,
            Boolean mainStream,
            Boolean falseBreakout,
            Boolean belowOpen,
            Boolean belowPrevClose,
            Boolean nearDayHigh,
            Boolean stopLossReasonable,
            String rationale,
            String entryPriceZone,
            Double stopLossPrice,
            Double takeProfit1,
            Double takeProfit2,
            BigDecimal javaStructureScore,
            BigDecimal claudeScore,
            BigDecimal codexScore,
            BigDecimal finalRankScore,
            Boolean    isVetoed,
            BigDecimal baseScore,
            Boolean    hasTheme,
            Integer    themeRank,
            BigDecimal finalThemeScore,
            BigDecimal consensusScore,
            BigDecimal disagreementPenalty,
            Boolean    volumeSpike,
            Boolean    priceNotBreakHigh,
            Boolean    entryTooExtended,
            Boolean    entryTriggered,
            BigDecimal currentPrice,
            BigDecimal openPrice,
            BigDecimal previousClose,
            BigDecimal vwapPrice,
            BigDecimal volumeRatio,
            BigDecimal distanceFromOpenPct,
            BigDecimal dropFromPrevClosePct,
            String     marketRegime,
            BigDecimal dayHigh
    ) {
        this(stockCode, stockName, valuationMode, entryType, riskRewardRatio,
                includeInFinalPlan, mainStream, falseBreakout, belowOpen, belowPrevClose,
                nearDayHigh, stopLossReasonable, rationale, entryPriceZone, stopLossPrice,
                takeProfit1, takeProfit2, javaStructureScore, claudeScore, codexScore,
                finalRankScore, isVetoed, baseScore, hasTheme, themeRank, finalThemeScore,
                consensusScore, disagreementPenalty, volumeSpike, priceNotBreakHigh,
                entryTooExtended, entryTriggered,
                currentPrice, openPrice, previousClose, vwapPrice, volumeRatio,
                distanceFromOpenPct, dropFromPrevClosePct, marketRegime,
                dayHigh,
                null);
    }

    // ── Wither methods（FinalDecisionEngine.tradabilityTag soft penalty / pipeline rebuild） ──

    /** 用於 FinalDecisionEngine soft-penalty path：扣分後重建一份 candidate。 */
    public FinalDecisionCandidateRequest withFinalRankScore(BigDecimal newScore) {
        return new FinalDecisionCandidateRequest(
                stockCode, stockName, valuationMode, entryType, riskRewardRatio,
                includeInFinalPlan, mainStream, falseBreakout, belowOpen, belowPrevClose,
                nearDayHigh, stopLossReasonable, rationale, entryPriceZone, stopLossPrice,
                takeProfit1, takeProfit2, javaStructureScore, claudeScore, codexScore,
                newScore, isVetoed, baseScore, hasTheme, themeRank, finalThemeScore,
                consensusScore, disagreementPenalty, volumeSpike, priceNotBreakHigh,
                entryTooExtended, entryTriggered,
                currentPrice, openPrice, previousClose, vwapPrice, volumeRatio,
                distanceFromOpenPct, dropFromPrevClosePct, marketRegime,
                dayHigh,
                tradabilityTag
        );
    }

    /** 用於 applyScoringPipeline 重建後補回 tradabilityTag。 */
    public FinalDecisionCandidateRequest withTradabilityTag(String newTag) {
        return new FinalDecisionCandidateRequest(
                stockCode, stockName, valuationMode, entryType, riskRewardRatio,
                includeInFinalPlan, mainStream, falseBreakout, belowOpen, belowPrevClose,
                nearDayHigh, stopLossReasonable, rationale, entryPriceZone, stopLossPrice,
                takeProfit1, takeProfit2, javaStructureScore, claudeScore, codexScore,
                finalRankScore, isVetoed, baseScore, hasTheme, themeRank, finalThemeScore,
                consensusScore, disagreementPenalty, volumeSpike, priceNotBreakHigh,
                entryTooExtended, entryTriggered,
                currentPrice, openPrice, previousClose, vwapPrice, volumeRatio,
                distanceFromOpenPct, dropFromPrevClosePct, marketRegime,
                dayHigh,
                newTag
        );
    }
}
