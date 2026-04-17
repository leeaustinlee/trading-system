package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record DailyPnlResponse(
        Long id,
        LocalDate tradingDate,
        BigDecimal realizedPnl,         // 舊欄位，保留供 summary 使用
        BigDecimal unrealizedPnl,
        BigDecimal winRate,
        BigDecimal grossPnl,            // 毛損益
        BigDecimal estimatedFeeAndTax,  // 費稅估算
        BigDecimal netPnl,              // 淨損益
        Integer tradeCount,
        Integer winCount,
        Integer lossCount,
        String notes,
        String payloadJson,
        LocalDateTime createdAt
) {
}
