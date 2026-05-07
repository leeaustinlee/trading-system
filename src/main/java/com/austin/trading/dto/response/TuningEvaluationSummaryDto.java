package com.austin.trading.dto.response;

import java.math.BigDecimal;

public record TuningEvaluationSummaryDto(
        long successCount,
        long failCount,
        BigDecimal successRate,
        TuningEvaluationResultDto latestSuccess,
        TuningEvaluationResultDto latestFail,
        long rollbackSuggestionCount
) {}
