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

public class EnterWeakPerformanceRule extends AbstractTuningRule {
    public EnterWeakPerformanceRule(TuningConfidenceCalculator calculator, ObjectMapper objectMapper) {
        super(calculator, objectMapper);
    }

    @Override
    public List<StrategyTuningRecommendationEntity> evaluate(LocalDate asOfDate, int lookbackDays,
            List<CandidateForwardTrackingEntity> candidateRows, List<MissedRallyTrackingEntity> missedRallyRows) {
        var stats = TuningStats.candidateStats(candidateRows.stream()
                .filter(r -> "ENTER".equalsIgnoreCase(r.getFinalDecision())).toList());
        if (stats.sampleSize() < TuningStats.MIN_SAMPLE) return List.of();
        if (!TuningStats.lt(stats.avgReturnPct(), "0") || stats.winRate().compareTo(new java.math.BigDecimal("45")) >= 0
                || stats.avgMaePct() == null || stats.avgMaePct().compareTo(new java.math.BigDecimal("-4")) >= 0) {
            return List.of();
        }
        String key = stats.avgMaePct().compareTo(new java.math.BigDecimal("-6")) < 0
                ? "risk.max_position_size_pct" : "scoring.enter_min_score";
        String suggested = key.startsWith("risk.") ? "0.18" : "6.8";
        TuningConfidence confidence = confidenceCalculator.calculate(stats.sampleSize(), stats.avgMaePct());
        return List.of(recommendation(asOfDate, lookbackDays,
                key.startsWith("risk.") ? TuningRecommendationType.POSITION_SIZE_REDUCE : TuningRecommendationType.THRESHOLD_TIGHTEN,
                key, key.startsWith("risk.") ? "0.20" : "6.5", suggested,
                "ENTER 樣本表現偏弱，建議收緊門檻或降低倉位；apply 前需人工審核。",
                "ENTER 平均報酬為負、勝率偏低且 MAE 偏大。",
                Map.of("rule", "EnterWeakPerformanceRule", "topStrategy", stats.topStrategy()),
                stats.sampleSize(), stats.winRate(), stats.avgReturnPct(), stats.avgMfePct(), stats.avgMaePct(),
                null, stats.avgRelativeReturnPct(), confidence));
    }
}
