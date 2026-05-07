package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PositionResponse(
        Long id,
        String symbol,
        String stockName,
        String side,
        BigDecimal qty,
        BigDecimal avgCost,
        String status,
        BigDecimal stopLossPrice,
        BigDecimal takeProfit1,
        BigDecimal takeProfit2,
        LocalDateTime openedAt,
        LocalDateTime closedAt,
        BigDecimal closePrice,
        String exitReason,
        BigDecimal realizedPnl,
        String note,
        String payloadJson,
        LocalDateTime createdAt,
        /** v2.3：SETUP / MOMENTUM_CHASE */
        String strategyType,
        /** v2.14：最近一次 position_review_log 的 decisionStatus（STRONG / WEAKEN / EXIT / null）。 */
        String reviewStatus,
        /** v2.14：最近一次 review 紀錄時間（review_date + review_time 合併）。 */
        LocalDateTime reviewedAt,
        /** v2.14：最近一次 review 的 reason 文字。 */
        String reviewReason,
        /** v2.18：持倉即時/收盤報價。盤外若 TWSE MIS 回收盤價也會填入。 */
        BigDecimal currentPrice,
        /** v2.18：目前市值 = currentPrice × qty。 */
        BigDecimal marketValue,
        /** v2.18：未實現損益 = (currentPrice - avgCost) × qty。 */
        BigDecimal unrealizedPnl,
        /** v2.18：未實現損益率。 */
        BigDecimal unrealizedPnlPct,
        /** v2.18：報價是否可用。 */
        Boolean quoteAvailable,
        /** v2.18：報價時間（HH:mm:ss）。 */
        String quoteTime
) {
    /** 向下相容 ctor（無 strategyType / 無 review）：補 SETUP + null。 */
    public PositionResponse(Long id, String symbol, String stockName, String side,
                             BigDecimal qty, BigDecimal avgCost, String status,
                             BigDecimal stopLossPrice, BigDecimal takeProfit1, BigDecimal takeProfit2,
                             LocalDateTime openedAt, LocalDateTime closedAt,
                             BigDecimal closePrice, String exitReason, BigDecimal realizedPnl,
                             String note, String payloadJson, LocalDateTime createdAt) {
        this(id, symbol, stockName, side, qty, avgCost, status,
                stopLossPrice, takeProfit1, takeProfit2,
                openedAt, closedAt, closePrice, exitReason, realizedPnl,
                note, payloadJson, createdAt, "SETUP", null, null, null,
                null, null, null, null, null, null);
    }

    /** 向下相容 ctor（有 strategyType、無 review）：補 review null。 */
    public PositionResponse(Long id, String symbol, String stockName, String side,
                             BigDecimal qty, BigDecimal avgCost, String status,
                             BigDecimal stopLossPrice, BigDecimal takeProfit1, BigDecimal takeProfit2,
                             LocalDateTime openedAt, LocalDateTime closedAt,
                             BigDecimal closePrice, String exitReason, BigDecimal realizedPnl,
                             String note, String payloadJson, LocalDateTime createdAt,
                             String strategyType) {
        this(id, symbol, stockName, side, qty, avgCost, status,
                stopLossPrice, takeProfit1, takeProfit2,
                openedAt, closedAt, closePrice, exitReason, realizedPnl,
                note, payloadJson, createdAt, strategyType, null, null, null,
                null, null, null, null, null, null);
    }
}
