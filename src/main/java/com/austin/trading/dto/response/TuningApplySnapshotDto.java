package com.austin.trading.dto.response;

import com.austin.trading.entity.TuningApplySnapshotEntity;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TuningApplySnapshotDto(
        Long id,
        Long recommendationId,
        LocalDate appliedDate,
        int lookbackDays,
        BigDecimal decisionWinRate,
        BigDecimal decisionAvgReturn,
        BigDecimal decisionAvgMfe,
        BigDecimal decisionAvgMae,
        String strategyMetricsJson,
        String gateMetricsJson,
        String scoreBucketMetricsJson
) {
    public static TuningApplySnapshotDto from(TuningApplySnapshotEntity e) {
        return new TuningApplySnapshotDto(e.getId(), e.getRecommendationId(), e.getAppliedDate(), e.getLookbackDays(),
                e.getDecisionWinRate(), e.getDecisionAvgReturn(), e.getDecisionAvgMfe(), e.getDecisionAvgMae(),
                e.getStrategyMetricsJson(), e.getGateMetricsJson(), e.getScoreBucketMetricsJson());
    }
}
