package com.austin.trading.dto.response;

import java.math.BigDecimal;

/**
 * 資金總覽（供 Dashboard 顯示 + Codex 決策參考）。
 *
 * <p><b>v3：</b>現金主帳由 {@code capital_ledger} 推導，{@code capital_config.available_cash} 不再是 truth-of-source。</p>
 *
 * @param availableCash       可動用現金 = cashBalance − reservedCash（舊欄位，維持向下相容）
 * @param cashBalance         現金餘額 = SUM(ledger.amount)
 * @param reservedCash        保留備用金（由 capital_config 設定）
 * @param investedCost        持倉成本（sum avgCost × qty）
 * @param investedValue       持倉現值（sum currentPrice × qty，盤外為 null）
 * @param unrealizedPnl       未實現損益（investedValue − investedCost，盤外為 null）
 * @param realizedPnl         近期已實現損益（由 daily_pnl 近 30 天累計）
 * @param totalEquity         總權益 = cashBalance + (investedValue ?? investedCost)
 * @param totalAssets         舊欄位，等同 totalEquity（保留欄位）
 * @param cashRatio           現金比例 % = availableCash / totalEquity × 100
 * @param openPositionCount   目前持倉檔數
 * @param liveQuoteAvailable  是否取得到即時報價
 * @param configNotes         資金設定備註
 * @param configUpdatedAt     設定最後更新時間
 */
public record CapitalSummaryResponse(
        BigDecimal availableCash,
        BigDecimal cashBalance,
        BigDecimal reservedCash,
        BigDecimal investedCost,
        BigDecimal investedValue,
        BigDecimal unrealizedPnl,
        BigDecimal realizedPnl,
        BigDecimal totalEquity,
        BigDecimal totalAssets,
        BigDecimal cashRatio,
        int        openPositionCount,
        boolean    liveQuoteAvailable,
        String     configNotes,
        String     configUpdatedAt
) {}
