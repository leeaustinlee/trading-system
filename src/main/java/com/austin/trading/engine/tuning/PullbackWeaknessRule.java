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

public class PullbackWeaknessRule extends AbstractTuningRule {
    public PullbackWeaknessRule(TuningConfidenceCalculator calculator, ObjectMapper objectMapper) {
        super(calculator, objectMapper);
    }

    @Override
    public List<StrategyTuningRecommendationEntity> evaluate(LocalDate asOfDate, int lookbackDays,
            List<CandidateForwardTrackingEntity> candidateRows, List<MissedRallyTrackingEntity> missedRallyRows) {
        var stats = TuningStats.candidateStats(candidateRows.stream()
                .filter(r -> "PULLBACK".equalsIgnoreCase(r.getPrimaryStrategy()))
                .filter(r -> r.getFinalDecision() == null || List.of("ENTER", "WAIT_PULLBACK").contains(r.getFinalDecision().toUpperCase()))
                .toList());
        if (stats.sampleSize() < TuningStats.MIN_SAMPLE) return List.of();
        if (!TuningStats.lt(stats.avgMfePct(), "4") || !TuningStats.lt(stats.avgReturnPct(), "1")) return List.of();
        TuningConfidence confidence = confidenceCalculator.calculate(stats.sampleSize(), stats.avgReturnPct());
        return List.of(recommendation(asOfDate, lookbackDays, TuningRecommendationType.THRESHOLD_TIGHTEN,
                "pullback.min_score", "7.0", "7.3",
                "Pullback 樣本 MFE 與收盤報酬偏弱，建議提高 Pullback 門檻。",
                "Pullback 表現弱，不建議延長等待，先收緊品質門檻。",
                Map.of("rule", "PullbackWeaknessRule", "sample", stats.sampleSize()),
                stats.sampleSize(), stats.winRate(), stats.avgReturnPct(), stats.avgMfePct(), stats.avgMaePct(),
                null, stats.avgRelativeReturnPct(), confidence));
    }
}
