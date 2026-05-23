package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 批次寫入候選股（含可選的 stock_evaluation 欄位）。
 *
 * <p>tradingDate 若不傳，Service 層會補上今日。
 * valuationMode、entryPriceZone 等評估欄位若有任一非 null，
 * 則同步 upsert stock_evaluation 表。</p>
 */
public record CandidateBatchItemRequest(
        LocalDate tradingDate,

        @NotBlank String symbol,
        String stockName,
        BigDecimal score,
        String reason,
        String themeTag,
        String sector,
        String payloadJson,

        // ── 評估欄位（選填）── //
        String valuationMode,
        String entryPriceZone,
        BigDecimal stopLossPrice,
        BigDecimal takeProfit1,
        BigDecimal takeProfit2,
        BigDecimal riskRewardRatio,
        Boolean includeInFinalPlan,

        // ── MVP-4 Theme-first / role-aware shadow integration（選填）── //
        String candidateRole,
        BigDecimal themeImportanceScore,
        BigDecimal tradableScore,
        BigDecimal shadowRankScore,
        String themeLeaderSymbol,
        Boolean isThemeLeader,
        Boolean leaderTradable,
        String leaderRetentionReason,
        String themeTraceId
) {
    public CandidateBatchItemRequest(
            LocalDate tradingDate,
            String symbol,
            String stockName,
            BigDecimal score,
            String reason,
            String themeTag,
            String sector,
            String payloadJson,
            String valuationMode,
            String entryPriceZone,
            BigDecimal stopLossPrice,
            BigDecimal takeProfit1,
            BigDecimal takeProfit2,
            BigDecimal riskRewardRatio,
            Boolean includeInFinalPlan
    ) {
        this(tradingDate, symbol, stockName, score, reason, themeTag, sector, payloadJson,
                valuationMode, entryPriceZone, stopLossPrice, takeProfit1, takeProfit2,
                riskRewardRatio, includeInFinalPlan,
                null, null, null, null, null, null, null, null, null);
    }
}
