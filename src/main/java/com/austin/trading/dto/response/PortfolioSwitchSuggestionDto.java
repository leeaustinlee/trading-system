package com.austin.trading.dto.response;

import com.austin.trading.domain.enums.SwitchDecision;

import java.math.BigDecimal;

public record PortfolioSwitchSuggestionDto(
        String sellStockId,
        String buyStockId,
        String buyStockName,
        String strategy,
        SwitchDecision decision,
        BigDecimal scoreGap,
        String riskNote,
        String reason
) {
}
