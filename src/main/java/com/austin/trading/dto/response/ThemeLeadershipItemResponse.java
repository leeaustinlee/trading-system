package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Read-only theme leadership row. Diagnostic only; never a BUY/SELL signal.
 */
public record ThemeLeadershipItemResponse(
        String symbol,
        String stockName,
        String themeTag,
        String themeCategory,
        String subTheme,
        Integer leaderRank,
        Integer hotStockRank,
        Integer superStrongRank,
        BigDecimal priceChangePct,
        BigDecimal turnover,
        BigDecimal score,
        Boolean closeNearHigh,
        Boolean tradable,
        String tradableReason,
        String retentionReason,
        String taxonomyStatus,
        List<String> divergenceFlags
) {
}
