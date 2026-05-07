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

public class RejectRallyTuningRule extends AbstractTuningRule {
    public RejectRallyTuningRule(TuningConfidenceCalculator calculator, ObjectMapper objectMapper) {
        super(calculator, objectMapper);
    }

    @Override
    public List<StrategyTuningRecommendationEntity> evaluate(LocalDate asOfDate, int lookbackDays,
            List<CandidateForwardTrackingEntity> candidateRows, List<MissedRallyTrackingEntity> missedRallyRows) {
        var stats = TuningStats.missedRallyStats(missedRallyRows.stream()
                .filter(r -> r.getOriginalDecision() != null && List.of("REJECT", "WAIT", "WATCH")
                        .contains(r.getOriginalDecision().toUpperCase()))
                .toList());
        if (stats.sampleSize() < TuningStats.MIN_SAMPLE) return List.of();
        if (!TuningStats.ge(stats.avgMaxReturnPct(), "6") || !TuningStats.ge(stats.missedRallyRate(), "25")) {
            return List.of();
        }
        TuningConfidence confidence = confidenceCalculator.calculate(stats.sampleSize(), stats.missedRallyRate());
        if (confidence == TuningConfidence.INSUFFICIENT_DATA) return List.of();
        String key = List.of("chased_high_block", "near_day_high").contains(stats.topGateName())
                ? "gate.near_day_high_reject_threshold" : "breakout.enter_small_enabled";
        String value = key.endsWith("enabled") ? "true" : "0.025";
        return List.of(recommendation(asOfDate, lookbackDays, TuningRecommendationType.GATE_RELAX,
                key, key.endsWith("enabled") ? "false" : "0.02", value,
                "放寬過嚴 gate，仍只建立 PENDING 建議，需人工審核後才能 apply。",
                "被拒絕或觀察標的後續大漲比例偏高，建議小幅放寬 gate 或改以 ENTER_SMALL 觀察。",
                Map.of("rule", "RejectRallyTuningRule", "topGate", stats.topGateName(),
                        "sample", stats.sampleSize(), "missedRallyCount", stats.missedRallyCount()),
                stats.sampleSize(), null, stats.avgCloseReturnPct(), stats.avgMfePct(), stats.avgMaePct(),
                stats.missedRallyRate(), null, confidence));
    }
}
