package com.austin.trading.dto.request;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record KolStructuredResultRequest(
        List<ThemeItem> themes,
        Map<String, Object> payload
) {
    public record ThemeItem(
            String themeTag,
            String direction,
            BigDecimal confidence,
            String summary,
            List<StockItem> stocks,
            List<EvidenceItem> evidence,
            Map<String, Object> payload
    ) {
    }

    public record StockItem(
            String symbol,
            String stockName,
            BigDecimal confidence,
            Map<String, Object> payload
    ) {
    }

    public record EvidenceItem(
            String evidenceType,
            String direction,
            String evidenceText,
            BigDecimal confidence,
            Map<String, Object> payload
    ) {
    }
}
