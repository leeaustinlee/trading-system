package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * v2.16 Batch C：GET /api/themes/exposure 整體回傳。
 */
public record ThemeExposureResponse(
        List<ThemeExposureItem> exposures,
        BigDecimal              totalCost,
        BigDecimal              totalEquity,
        BigDecimal              limitPct,
        BigDecimal              warnPct,
        LocalDateTime           timestamp
) {}
