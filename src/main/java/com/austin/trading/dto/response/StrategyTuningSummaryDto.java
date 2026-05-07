package com.austin.trading.dto.response;

public record StrategyTuningSummaryDto(
        long pendingCount,
        StrategyTuningRecommendationDto latestHighConfidenceRecommendation,
        String warningMessage
) {
}
