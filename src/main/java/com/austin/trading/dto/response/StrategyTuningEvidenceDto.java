package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.util.Map;

public record StrategyTuningEvidenceDto(
        int sampleSize,
        BigDecimal winRate,
        BigDecimal avgReturnPct,
        BigDecimal avgMfePct,
        BigDecimal avgMaePct,
        BigDecimal missedRallyRate,
        BigDecimal avgMaxReturnPct,
        BigDecimal benchmarkRelativeReturnPct,
        Map<String, Object> raw
) {
}
