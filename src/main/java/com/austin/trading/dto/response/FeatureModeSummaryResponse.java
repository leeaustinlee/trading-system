package com.austin.trading.dto.response;

import com.austin.trading.domain.enums.FeatureRuntimeMode;

import java.time.LocalDateTime;
import java.util.Map;

public record FeatureModeSummaryResponse(
        LocalDateTime generatedAt,
        int total,
        Map<FeatureRuntimeMode, Long> counts,
        long liveDecisionAffecting,
        String safetyNote
) {
}
