package com.austin.trading.dto.request;

import java.math.BigDecimal;

/**
 * 手動覆蓋每日損益（券商確認後補入實際數字）。
 * 所有欄位皆為 nullable，只更新有值的欄位。
 */
public record DailyPnlUpdateRequest(
        BigDecimal grossPnl,
        BigDecimal estimatedFeeAndTax,
        BigDecimal netPnl,
        Integer tradeCount,
        Integer winCount,
        Integer lossCount,
        String notes
) {
}
