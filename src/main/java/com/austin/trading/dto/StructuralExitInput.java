package com.austin.trading.dto;

import java.math.BigDecimal;

public record StructuralExitInput(
        String symbol,
        BigDecimal currentPrice,
        BigDecimal trailingStopPrice,
        Integer healthScore,
        String structureStatus,
        String volumeStatus,
        String relativeStrengthStatus,
        String chipStatus,
        Boolean mainstreamTheme
) {
}
