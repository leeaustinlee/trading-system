package com.austin.trading.engine.tuning;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.MissedRallyTrackingEntity;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;

import java.time.LocalDate;
import java.util.List;

public interface TuningRule {
    List<StrategyTuningRecommendationEntity> evaluate(
            LocalDate asOfDate,
            int lookbackDays,
            List<CandidateForwardTrackingEntity> candidateRows,
            List<MissedRallyTrackingEntity> missedRallyRows
    );
}
