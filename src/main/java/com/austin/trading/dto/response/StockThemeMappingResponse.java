package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StockThemeMappingResponse(
        Long id,
        String symbol,
        String stockName,
        String themeTag,
        String subTheme,
        String themeCategory,
        String source,
        BigDecimal confidence,
        Boolean isActive,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
