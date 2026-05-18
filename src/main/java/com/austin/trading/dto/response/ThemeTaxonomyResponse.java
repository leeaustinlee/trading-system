package com.austin.trading.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-only Theme Taxonomy truth-layer response.
 */
public record ThemeTaxonomyResponse(
        LocalDate tradingDate,
        int themeCount,
        boolean activeOnly,
        String safetyNote,
        LocalDateTime generatedAt,
        List<ThemeTaxonomyItemResponse> items
) {
}
