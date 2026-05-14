package com.austin.trading.dto.response;

import java.math.BigDecimal;

/**
 * Shadow-only RR gate 計算結果；只供診斷，不可直接改變 production BUY path。
 */
public record RiskRewardShadowGateResult(
        BigDecimal rrValue,
        BigDecimal minRequiredRr,
        String shadowStatus,
        String reason,
        BigDecimal entryPrice,
        BigDecimal stopPrice,
        BigDecimal target1,
        BigDecimal target2
) {
}
