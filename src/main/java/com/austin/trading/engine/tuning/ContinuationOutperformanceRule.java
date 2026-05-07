package com.austin.trading.engine.tuning;

import com.austin.trading.domain.enums.TuningConfidence;
import com.austin.trading.domain.enums.TuningRecommendationType;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.MissedRallyTrackingEntity;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public class ContinuationOutperformanceRule extends AbstractTuningRule {
    public ContinuationOutperformanceRule(TuningConfidenceCalculator calculator, ObjectMapper objectMapper) {
        super(calculator, objectMapper);
    }

    @Override
    public List<StrategyTuningRecommendationEntity> evaluate(LocalDate asOfDate, int lookbackDays,
            List<CandidateForwardTrackingEntity> candidateRows, List<MissedRallyTrackingEntity> missedRallyRows) {
        var stats = TuningStats.candidateStats(candidateRows.stream()
                .filter(r -> "CONTINUATION".equalsIgnoreCase(r.getPrimaryStrategy()))
                .filter(r -> "ENTER".equalsIgnoreCase(r.getFinalDecision()))
                .toList());
        if (stats.sampleSize() < TuningStats.MIN_SAMPLE) return List.of();
        if (stats.winRate().compareTo(new java.math.BigDecimal("55")) < 0
                || stats.avgMaePct() == null || stats.avgMaePct().compareTo(new java.math.BigDecimal("-3")) <= 0
                || !TuningStats.ge(stats.avgT3ReturnPct(), "2") || !TuningStats.ge(stats.avgReturnPct(), "3")) {
            return List.of();
        }
        TuningConfidence confidence = confidenceCalculator.calculate(stats.sampleSize(), stats.avgReturnPct());
        return List.of(recommendation(asOfDate, lookbackDays, TuningRecommendationType.THRESHOLD_RELAX,
                "continuation.rr_min", "1.5", "1.4",
                "Continuation 表現穩定優於門檻，建議小幅放寬 RR 下限；不自動套用。",
                "Continuation ENTER 勝率、T3/T5 報酬與 MAE 均達標。",
                Map.of("rule", "ContinuationOutperformanceRule", "sample", stats.sampleSize()),
                stats.sampleSize(), stats.winRate(), stats.avgReturnPct(), stats.avgMfePct(), stats.avgMaePct(),
                null, stats.avgRelativeReturnPct(), confidence));
    }
}
