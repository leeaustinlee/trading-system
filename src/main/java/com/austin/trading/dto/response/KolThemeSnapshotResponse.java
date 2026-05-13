package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record KolThemeSnapshotResponse(
        Long id,
        LocalDate tradingDate,
        String themeTag,
        String direction,
        int sourceCount,
        int evidenceCount,
        BigDecimal positiveScore,
        BigDecimal negativeScore,
        BigDecimal netShadowBoost,
        String crowdingRisk,
        String topSourcesJson,
        String payloadJson
) {
}
