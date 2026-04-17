package com.austin.trading.dto.response;

import java.math.BigDecimal;

/**
 * 資金總覽（供 Dashboard 顯示 + Codex 決策參考）。
 *
 * @param availableCash       可動用現金（用戶設定）
 * @param investedCost        持倉成本（sum avgCost × qty）
 * @param investedValue       持倉現值（sum currentPrice × qty，盤外為 null）
 * @param unrealizedPnl       未實現損益（investedValue − investedCost，盤外為 null）
 * @param totalAssets         總資產估計（availableCash + investedValue；盤外用成本估算）
 * @param cashRatio           現金比例 % = availableCash / totalAssets × 100
 * @param openPositionCount   目前持倉檔數
 * @param liveQuoteAvailable  是否取得到即時報價
 * @param configNotes         資金設定備註
 * @param configUpdatedAt     設定最後更新時間
 */
public record CapitalSummaryResponse(
        BigDecimal availableCash,
        BigDecimal investedCost,
        BigDecimal investedValue,
        BigDecimal unrealizedPnl,
        BigDecimal totalAssets,
        BigDecimal cashRatio,
        int        openPositionCount,
        boolean    liveQuoteAvailable,
        String     configNotes,
        String     configUpdatedAt
) {}
