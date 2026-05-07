package com.austin.trading.dto.response;

import com.austin.trading.entity.TuningAfterMetricsEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TuningAfterMetricsDto(
        Long id,
        Long recommendationId,
        LocalDate evaluationDate,
        int horizonDays,
        int sampleSize,
        BigDecimal winRate,
        BigDecimal avgReturn,
        BigDecimal avgMfe,
        BigDecimal avgMae,
        BigDecimal avgRelativeReturn,
        BigDecimal benchmarkReturn
) {
    public static TuningAfterMetricsDto from(TuningAfterMetricsEntity e) {
        return new TuningAfterMetricsDto(e.getId(), e.getRecommendationId(), e.getEvaluationDate(), e.getHorizonDays(),
                e.getSampleSize(), e.getWinRate(), e.getAvgReturn(), e.getAvgMfe(), e.getAvgMae(),
                e.getAvgRelativeReturn(), e.getBenchmarkReturn());
    }
}
