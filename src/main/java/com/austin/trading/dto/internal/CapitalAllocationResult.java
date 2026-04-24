package com.austin.trading.dto.internal;

import com.austin.trading.domain.enums.AllocationAction;
import com.austin.trading.domain.enums.AllocationMode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * v2.11 CapitalAllocationEngine 輸出（MVP）。
 *
 * <p>只做「建議」。FinalDecisionService / PositionManagementService 不會據此下單，
 * 只把金額 / 股數塞進 decision trace 與 LINE 訊息。</p>
 */
public record CapitalAllocationResult(
        String symbol,
        AllocationAction action,
        AllocationMode mode,
        BigDecimal suggestedAmount,
        Integer suggestedShares,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal riskPerShare,
        BigDecimal maxLossAmount,
        BigDecimal estimatedLossAmount,
        BigDecimal positionPctOfEquity,
        BigDecimal portfolioExposureAfter,
        BigDecimal themeExposureAfter,
        /** 減碼 / 換股情境下給的「減碼比例（0–1）」，其餘情境為 null。 */
        BigDecimal suggestedReducePct,
        List<String> reasons,
        List<String> warnings,
        Map<String, Object> trace
) {
    public boolean isBlocked() {
        return action == AllocationAction.RISK_BLOCK || action == AllocationAction.CASH_RESERVE;
    }
}
