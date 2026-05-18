package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read-only stock_theme_mapping coverage and quality dashboard.
 */
public record ThemeMappingObservabilityResponse(
        long totalMappings,
        long activeMappings,
        long inactiveMappings,
        long distinctSymbols,
        long distinctThemes,
        Map<String, Long> byCategory,
        Map<String, Long> bySource,
        long missingCategoryCount,
        long missingSourceCount,
        long missingConfidenceCount,
        long lowConfidenceCount,
        long ambiguousSymbolCount,
        long otherCategoryCount,
        BigDecimal otherCategoryRatio,
        BigDecimal lowConfidenceThreshold,
        String safetyNote,
        LocalDateTime generatedAt,
        List<ThemeMappingIssueResponse> issues,
        List<StockThemeMappingResponse> mappings
) {
}
