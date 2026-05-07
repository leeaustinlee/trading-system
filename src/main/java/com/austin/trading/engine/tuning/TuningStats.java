package com.austin.trading.engine.tuning;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.MissedRallyTrackingEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class TuningStats {
    public static final int MIN_SAMPLE = 20;

    private TuningStats() {}

    public static CandidateStats candidateStats(List<CandidateForwardTrackingEntity> rows) {
        List<CandidateForwardTrackingEntity> valid = rows.stream()
                .filter(r -> r.getT5CloseReturnPct() != null || r.getMfePct() != null || r.getMaePct() != null)
                .toList();
        int sample = valid.size();
        int wins = (int) valid.stream().filter(r -> gt(r.getT5CloseReturnPct(), BigDecimal.ZERO)).count();
        return new CandidateStats(
                sample,
                pct(wins, sample),
                avg(valid.stream().map(CandidateForwardTrackingEntity::getT5CloseReturnPct).toList()),
                avg(valid.stream().map(CandidateForwardTrackingEntity::getT3CloseReturnPct).toList()),
                avg(valid.stream().map(CandidateForwardTrackingEntity::getMfePct).toList()),
                avg(valid.stream().map(CandidateForwardTrackingEntity::getMaePct).toList()),
                avg(valid.stream().map(CandidateForwardTrackingEntity::getRelativeReturnPct).toList()),
                topValue(valid.stream().map(CandidateForwardTrackingEntity::getGateName).toList()),
                topValue(valid.stream().map(CandidateForwardTrackingEntity::getPrimaryStrategy).toList())
        );
    }

    public static MissedRallyStats missedRallyStats(List<MissedRallyTrackingEntity> rows) {
        List<MissedRallyTrackingEntity> valid = rows.stream()
                .filter(r -> r.getMaxReturnPct() != null || r.getMfePct() != null || r.getMaePct() != null)
                .toList();
        int sample = valid.size();
        int missed = (int) valid.stream().filter(r -> Boolean.TRUE.equals(r.getMissedRallyFlag())).count();
        return new MissedRallyStats(
                sample,
                missed,
                pct(missed, sample),
                avg(valid.stream().map(MissedRallyTrackingEntity::getMaxReturnPct).toList()),
                avg(valid.stream().map(MissedRallyTrackingEntity::getCloseReturnPct).toList()),
                avg(valid.stream().map(MissedRallyTrackingEntity::getMfePct).toList()),
                avg(valid.stream().map(MissedRallyTrackingEntity::getMaePct).toList()),
                topValue(valid.stream().map(MissedRallyTrackingEntity::getGateName).toList()),
                topValue(valid.stream().map(MissedRallyTrackingEntity::getPrimaryStrategy).toList())
        );
    }

    public static BigDecimal avg(List<BigDecimal> values) {
        List<BigDecimal> clean = values.stream().filter(Objects::nonNull).toList();
        if (clean.isEmpty()) return null;
        BigDecimal sum = clean.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(new BigDecimal(clean.size()), 4, RoundingMode.HALF_UP);
    }

    public static BigDecimal pct(int numerator, int denominator) {
        if (denominator <= 0) return BigDecimal.ZERO;
        return new BigDecimal(numerator).multiply(new BigDecimal("100"))
                .divide(new BigDecimal(denominator), 4, RoundingMode.HALF_UP);
    }

    public static boolean ge(BigDecimal a, String b) { return a != null && a.compareTo(new BigDecimal(b)) >= 0; }
    public static boolean gt(BigDecimal a, BigDecimal b) { return a != null && a.compareTo(b) > 0; }
    public static boolean lt(BigDecimal a, String b) { return a != null && a.compareTo(new BigDecimal(b)) < 0; }

    private static String topValue(List<String> values) {
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .collect(Collectors.groupingBy(v -> v, Collectors.counting()))
                .entrySet().stream()
                .max(Comparator.comparingLong(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElse("UNKNOWN");
    }

    public record CandidateStats(
            int sampleSize,
            BigDecimal winRate,
            BigDecimal avgReturnPct,
            BigDecimal avgT3ReturnPct,
            BigDecimal avgMfePct,
            BigDecimal avgMaePct,
            BigDecimal avgRelativeReturnPct,
            String topGateName,
            String topStrategy
    ) {}

    public record MissedRallyStats(
            int sampleSize,
            int missedRallyCount,
            BigDecimal missedRallyRate,
            BigDecimal avgMaxReturnPct,
            BigDecimal avgCloseReturnPct,
            BigDecimal avgMfePct,
            BigDecimal avgMaePct,
            String topGateName,
            String topStrategy
    ) {}
}
