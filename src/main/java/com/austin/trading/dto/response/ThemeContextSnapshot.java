package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record ThemeContextSnapshot(
        String themeName,
        String themeLifecycle,
        BigDecimal themeHeat,
        BigDecimal themeBreadth,
        BigDecimal rotationStrength,
        String institutionalAlignment,
        String retailCrowding,
        BigDecimal narrativeHeat,
        BigDecimal waveStrength,
        String sectorLeadership,
        BigDecimal crowdingScore,
        Map<String, Object> marketContext,
        boolean themeStillActive,
        boolean productionDecisionAllowed,
        boolean autoBuyEnabled,
        boolean autoSellEnabled,
        boolean manualConfirmRequired,
        LocalDate tradingDate,
        String dataStatus,
        LocalDate latestValidTradingDate,
        boolean futureDataDetected,
        long staleDays,
        LocalDate latestDataDate,
        String dataFreshnessStatus
) {
    public static ThemeContextSnapshot unknown(String themeName) {
        return new ThemeContextSnapshot(
                themeName, "UNKNOWN", null, null, null, "UNKNOWN", "UNKNOWN", null, null,
                "UNKNOWN", null, Map.of("dataStatus", "MISSING_THEME_CONTEXT"), false,
                false, false, false, true, null,
                "EMPTY", null, false, 0, null, "EMPTY");
    }
}
