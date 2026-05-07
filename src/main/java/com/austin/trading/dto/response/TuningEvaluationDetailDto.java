package com.austin.trading.dto.response;

import java.util.List;

public record TuningEvaluationDetailDto(
        StrategyTuningRecommendationDto recommendation,
        TuningApplySnapshotDto beforeMetrics,
        List<TuningAfterMetricsDto> afterMetrics,
        TuningEvaluationResultDto evaluationResult
) {}
