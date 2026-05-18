package com.austin.trading.dto.response;

import java.math.BigDecimal;

/**
 * Per-mapping diagnostics for read-only theme observability.
 */
public record ThemeMappingIssueResponse(
        Long id,
        String symbol,
        String stockName,
        String themeTag,
        String themeCategory,
        String source,
        BigDecimal confidence,
        boolean active,
        String issueType,
        String issueDetail
) {
}
