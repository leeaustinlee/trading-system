package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Read-only Theme Taxonomy truth-layer row.
 *
 * This response is intentionally observability-only. It must not be used as a
 * BUY/SELL/FinalDecision signal.
 */
public record ThemeTaxonomyItemResponse(
        String themeTag,
        String themeCategory,
        List<String> subThemes,
        long activeStockCount,
        Map<String, Long> mappingSources,
        BigDecimal avgConfidence,
        BigDecimal finalThemeScore,
        BigDecimal marketBehaviorScore,
        BigDecimal themeHeatScore,
        BigDecimal themeContinuationScore,
        Integer rankingOrder,
        String leadingStockSymbol
) {
}
