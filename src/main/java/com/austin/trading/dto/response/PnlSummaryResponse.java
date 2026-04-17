package com.austin.trading.dto.response;

import java.math.BigDecimal;

public record PnlSummaryResponse(
        BigDecimal totalRealizedPnl,
        BigDecimal totalUnrealizedPnl,
        BigDecimal avgWinRate,
        int days
) {
}
