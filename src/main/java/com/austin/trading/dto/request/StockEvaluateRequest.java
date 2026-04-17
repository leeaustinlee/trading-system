package com.austin.trading.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 個股估值引擎輸入。
 * valuationScore: 0-100，由呼叫方根據籌碼/基本面評分傳入。
 *   0-30  → VALUE_LOW
 *   31-55 → VALUE_FAIR
 *   56-75 → VALUE_HIGH
 *   76+   → VALUE_STORY
 */
public record StockEvaluateRequest(
        @NotBlank String symbol,
        @NotBlank String marketGrade,
        @NotBlank String entryType,
        @NotNull Double entryPrice,
        @NotNull Double stopLossPercent,
        @NotNull Double takeProfit1Percent,
        @NotNull Double takeProfit2Percent,
        @NotNull Boolean volatileStock,
        @NotNull Boolean nearDayHigh,
        @NotNull Boolean aboveOpen,
        @NotNull Boolean abovePrevClose,
        @NotNull Boolean mainStream,
        @NotNull Integer valuationScore
) {
}
