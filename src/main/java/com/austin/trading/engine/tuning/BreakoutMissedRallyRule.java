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

public class BreakoutMissedRallyRule extends AbstractTuningRule {
    public BreakoutMissedRallyRule(TuningConfidenceCalculator calculator, ObjectMapper objectMapper) {
        super(calculator, objectMapper);
    }

    @Override
    public List<StrategyTuningRecommendationEntity> evaluate(LocalDate asOfDate, int lookbackDays,
            List<CandidateForwardTrackingEntity> candidateRows, List<MissedRallyTrackingEntity> missedRallyRows) {
        var stats = TuningStats.missedRallyStats(missedRallyRows.stream()
                .filter(r -> "BREAKOUT".equalsIgnoreCase(r.getPrimaryStrategy()))
                .filter(r -> r.getOriginalDecision() == null || List.of("REJECT", "WATCH").contains(r.getOriginalDecision().toUpperCase()))
                .toList());
        if (stats.sampleSize() < TuningStats.MIN_SAMPLE) return List.of();
        if (!TuningStats.ge(stats.avgMfePct(), "7") || !TuningStats.ge(stats.missedRallyRate(), "30")) return List.of();
        if (!List.of("near_day_high", "chased_high_block").contains(stats.topGateName())) return List.of();
        TuningConfidence confidence = confidenceCalculator.calculate(stats.sampleSize(), stats.missedRallyRate());
        return List.of(recommendation(asOfDate, lookbackDays, TuningRecommendationType.GATE_RELAX,
                "breakout.near_high_allowed", "false", "true",
                "Breakout 錯殺比例偏高，建議 nearDayHigh 不再作 hard reject，改由小倉位控風險。",
                "Breakout 被 nearDayHigh/chasedHigh 擋下後仍有高 MFE 與 missed rally。",
                Map.of("rule", "BreakoutMissedRallyRule", "topGate", stats.topGateName()),
                stats.sampleSize(), null, stats.avgCloseReturnPct(), stats.avgMfePct(), stats.avgMaePct(),
                stats.missedRallyRate(), null, confidence));
    }
}
