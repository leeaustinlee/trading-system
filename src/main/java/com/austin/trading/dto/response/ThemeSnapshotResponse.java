package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ThemeSnapshotResponse(
        Long id,
        LocalDate tradingDate,
        String themeTag,
        String themeCategory,
        BigDecimal marketBehaviorScore,
        BigDecimal totalTurnover,
        BigDecimal avgGainPct,
        Integer strongStockCount,
        String leadingStockSymbol,
        BigDecimal themeHeatScore,
        BigDecimal themeContinuationScore,
        String driverType,
        String riskSummary,
        BigDecimal finalThemeScore,
        Integer rankingOrder
) {}
