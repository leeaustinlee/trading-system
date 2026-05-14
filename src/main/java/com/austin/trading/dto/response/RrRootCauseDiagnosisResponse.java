package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.util.List;

public record RrRootCauseDiagnosisResponse(
        int days,
        int totalTrades,
        int analyzedTrades,
        int lowRrTradeCount,
        BigDecimal lowRrPct,
        BigDecimal avgRiskReward,
        BigDecimal avgEntryToStopPct,
        BigDecimal avgTarget1GainPct,
        BigDecimal avgTarget2GainPct,
        List<RootCauseBucket> rootCauseBuckets,
        ShadowImpact shadowImpact,
        List<String> dataGaps
) {
    public record RootCauseBucket(
            String name,
            int count,
            BigDecimal pct,
            List<String> sampleSymbols,
            String reason
    ) {
    }

    public record ShadowImpact(
            int wouldBlockCount,
            BigDecimal wouldBlockPct,
            BigDecimal blockedAvgForwardReturnT1,
            BigDecimal blockedAvgForwardReturnT3,
            BigDecimal blockedAvgForwardReturnT5,
            BigDecimal blockedAvgForwardReturnT10,
            BigDecimal blockedWinRate,
            int missedWinnerCount,
            int avoidedLoserCount,
            String status,
            List<String> dataGaps
    ) {
    }
}
