package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record KolThemeContextResponse(
        LocalDate tradingDate,
        String guardrail,
        List<ThemeContext> themes
) {
    public record ThemeContext(
            String themeTag,
            String direction,
            BigDecimal kolBoostShadow,
            String crowdingRisk,
            int sourceCount,
            int evidenceCount
    ) {
    }
}
