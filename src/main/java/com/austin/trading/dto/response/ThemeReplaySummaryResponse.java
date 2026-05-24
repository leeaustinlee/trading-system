package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ThemeReplaySummaryResponse(
        LocalDate tradingDate,
        String themeTag,
        String lifecycleStage,
        String leaderSymbol,
        int leaderCount,
        int peerCount,
        int breadth,
        int taxonomyGapCount,
        int divergenceCount,
        int riskRejectedCount,
        int researchUniverseCount,
        int tradableUniverseCount,
        BigDecimal replayScore,
        boolean shadowOnly,
        boolean replayOnly,
        ThemeReplayTimelineResponse.SafetyBoundary safetyBoundary
) {
}
