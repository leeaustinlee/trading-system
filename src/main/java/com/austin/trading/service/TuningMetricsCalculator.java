package com.austin.trading.service;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.TuningAfterMetricsEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Function;

final class TuningMetricsCalculator {
    private TuningMetricsCalculator() {}

    static Metrics calculate(List<CandidateForwardTrackingEntity> rows, int horizonDays) {
        Function<CandidateForwardTrackingEntity, BigDecimal> returnGetter = switch (horizonDays) {
            case 10 -> CandidateForwardTrackingEntity::getT10CloseReturnPct;
            case 20 -> r -> null;
            default -> CandidateForwardTrackingEntity::getT5CloseReturnPct;
        };
        List<CandidateForwardTrackingEntity> usable = rows.stream()
                .filter(r -> returnGetter.apply(r) != null)
                .toList();
        int sample = usable.size();
        if (sample == 0) return new Metrics(0, null, null, null, null, null, null);
        long wins = usable.stream().filter(r -> returnGetter.apply(r).compareTo(BigDecimal.ZERO) > 0).count();
        return new Metrics(sample,
                scale(BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(sample), 6, RoundingMode.HALF_UP)),
                average(usable, returnGetter),
                average(usable, CandidateForwardTrackingEntity::getMfePct),
                average(usable, CandidateForwardTrackingEntity::getMaePct),
                average(usable, CandidateForwardTrackingEntity::getRelativeReturnPct),
                average(usable, CandidateForwardTrackingEntity::getBenchmarkReturnPct));
    }

    static TuningAfterMetricsEntity toAfterMetrics(Long recommendationId, java.time.LocalDate evaluationDate,
                                                   int horizonDays, Metrics metrics) {
        TuningAfterMetricsEntity entity = new TuningAfterMetricsEntity();
        entity.setRecommendationId(recommendationId);
        entity.setEvaluationDate(evaluationDate);
        entity.setHorizonDays(horizonDays);
        entity.setSampleSize(metrics.sampleSize());
        entity.setWinRate(metrics.winRate());
        entity.setAvgReturn(metrics.avgReturn());
        entity.setAvgMfe(metrics.avgMfe());
        entity.setAvgMae(metrics.avgMae());
        entity.setAvgRelativeReturn(metrics.avgRelativeReturn());
        entity.setBenchmarkReturn(metrics.benchmarkReturn());
        return entity;
    }

    private static BigDecimal average(List<CandidateForwardTrackingEntity> rows,
                                      Function<CandidateForwardTrackingEntity, BigDecimal> getter) {
        List<BigDecimal> values = rows.stream().map(getter).filter(v -> v != null).toList();
        if (values.isEmpty()) return null;
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return scale(sum.divide(BigDecimal.valueOf(values.size()), 6, RoundingMode.HALF_UP));
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    record Metrics(int sampleSize,
                   BigDecimal winRate,
                   BigDecimal avgReturn,
                   BigDecimal avgMfe,
                   BigDecimal avgMae,
                   BigDecimal avgRelativeReturn,
                   BigDecimal benchmarkReturn) {}
}
