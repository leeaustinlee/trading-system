package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record StrategyTuningRecommendationDto(
        Long id,
        LocalDate generatedDate,
        int lookbackDays,
        String recommendationType,
        String targetModule,
        String targetParameter,
        String currentValue,
        String suggestedValue,
        String suggestedAction,
        String reason,
        String evidenceJson,
        Integer sampleSize,
        BigDecimal winRate,
        BigDecimal avgReturnPct,
        BigDecimal avgMfePct,
        BigDecimal avgMaePct,
        BigDecimal missedRallyRate,
        BigDecimal benchmarkRelativeReturnPct,
        String confidence,
        String status,
        String approvedBy,
        LocalDateTime approvedAt,
        String rejectedBy,
        LocalDateTime rejectedAt,
        LocalDateTime appliedAt,
        String rollbackValue,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
