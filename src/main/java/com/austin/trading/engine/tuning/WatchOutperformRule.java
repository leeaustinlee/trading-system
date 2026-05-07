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

public class WatchOutperformRule extends AbstractTuningRule {
    public WatchOutperformRule(TuningConfidenceCalculator calculator, ObjectMapper objectMapper) {
        super(calculator, objectMapper);
    }

    @Override
    public List<StrategyTuningRecommendationEntity> evaluate(LocalDate asOfDate, int lookbackDays,
            List<CandidateForwardTrackingEntity> candidateRows, List<MissedRallyTrackingEntity> missedRallyRows) {
        var watch = TuningStats.candidateStats(candidateRows.stream().filter(r -> "WATCH".equalsIgnoreCase(r.getFinalDecision())).toList());
        var enter = TuningStats.candidateStats(candidateRows.stream().filter(r -> "ENTER".equalsIgnoreCase(r.getFinalDecision())).toList());
        if (watch.sampleSize() < TuningStats.MIN_SAMPLE || enter.sampleSize() < TuningStats.MIN_SAMPLE) return List.of();
        if (watch.avgReturnPct() == null || enter.avgReturnPct() == null || watch.avgMfePct() == null || enter.avgMfePct() == null) return List.of();
        if (watch.avgReturnPct().subtract(enter.avgReturnPct()).compareTo(new java.math.BigDecimal("1.5")) <= 0
                || watch.winRate().subtract(enter.winRate()).compareTo(new java.math.BigDecimal("5")) <= 0
                || watch.avgMfePct().compareTo(enter.avgMfePct()) <= 0) return List.of();
        TuningConfidence confidence = confidenceCalculator.calculate(watch.sampleSize(), watch.avgReturnPct().subtract(enter.avgReturnPct()));
        return List.of(recommendation(asOfDate, lookbackDays, TuningRecommendationType.ENTER_SMALL,
                "breakout.enter_small_enabled", "false", "true",
                "WATCH 明顯優於 ENTER，建議允許部分 WATCH 走 ENTER_SMALL；不自動套用。",
                "WATCH 報酬、勝率與 MFE 均優於 ENTER。",
                Map.of("rule", "WatchOutperformRule", "watchSample", watch.sampleSize(), "enterSample", enter.sampleSize()),
                watch.sampleSize(), watch.winRate(), watch.avgReturnPct(), watch.avgMfePct(), watch.avgMaePct(),
                null, watch.avgRelativeReturnPct(), confidence));
    }
}
