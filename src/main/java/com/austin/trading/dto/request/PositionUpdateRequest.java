package com.austin.trading.dto.request;

import java.math.BigDecimal;

/**
 * 更新持倉（停損停利/加減碼/備註）。
 * 所有欄位皆為 nullable，只更新有值的欄位。
 */
public record PositionUpdateRequest(
        BigDecimal qty,             // 加減碼後的總股數（null = 不改）
        BigDecimal avgCost,         // 加減碼後的均價（null = 不改）
        BigDecimal stopLossPrice,
        BigDecimal takeProfit1,
        BigDecimal takeProfit2,
        String note
) {}
