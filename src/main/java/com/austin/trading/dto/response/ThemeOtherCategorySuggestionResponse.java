package com.austin.trading.dto.response;

import java.math.BigDecimal;

/**
 * Read-only review aid for generic OTHER theme mappings.
 */
public record ThemeOtherCategorySuggestionResponse(
        Long mappingId,
        String symbol,
        String stockName,
        String themeTag,
        String currentCategory,
        String suggestedCategory,
        String reason,
        String source,
        BigDecimal confidence,
        boolean active
) {
}
