package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Read-only lifecycle calibration diagnostics.
 *
 * <p>This response is advisory-only and must not be used by BUY/SELL/final decision,
 * production ranking, risk approval, candidate promotion, or execution paths.</p>
 */
public record ThemeLifecycleCalibrationResponse(
        boolean readOnly,
        boolean advisoryOnly,
        boolean doesNotAffectBuySell,
        int requestedDays,
        LocalDate requestedStartDate,
        LocalDate requestedEndDate,
        LocalDate actualStartDate,
        LocalDate actualEndDate,
        int actualAvailableDays,
        Map<String, DataCoverage> dataCoverage,
        List<StageDistribution> stageDistribution,
        FunnelSummary funnelSummary,
        ThemeAdmissionSummary themeAdmissionSummary,
        List<TopNShadowBucket> topNShadowSummary,
        PredictivePower lifecycleMetricPredictivePower,
        List<CalibrationFinding> calibrationFindings,
        List<String> dataGaps
) {
    public static ThemeLifecycleCalibrationResponse empty(int days, LocalDate start, LocalDate end, List<String> dataGaps) {
        return new ThemeLifecycleCalibrationResponse(
                true, true, true, days, start, end, null, null, 0,
                Map.of(), List.of(), FunnelSummary.empty(), ThemeAdmissionSummary.empty(),
                List.of(), PredictivePower.insufficient(0, "NO_JOINED_PAPER_TRADE_LIFECYCLE_SAMPLE"),
                List.of(), dataGaps);
    }

    public record DataCoverage(
            String tableName,
            long rowCount,
            LocalDate firstDate,
            LocalDate lastDate,
            int availableDays,
            boolean tableAvailable,
            String dataGapReason
    ) {}

    public record StageDistribution(
            String stage,
            long sampleCount,
            BigDecimal avgLifecycleScore,
            BigDecimal avgContinuationDays,
            BigDecimal avgBreadth,
            BigDecimal avgCrowdingScore
    ) {}

    public record FunnelSummary(
            long hotGroupSignalCount,
            long candidateHitCount,
            long watchlistHitCount,
            long rankingHitCount,
            long riskPassCount,
            long buyCount,
            Map<String, BigDecimal> conversionRates,
            Map<String, Long> blockedStageDistribution,
            Map<String, Long> topBlockedReasons
    ) {
        public static FunnelSummary empty() {
            return new FunnelSummary(0, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of());
        }
    }

    public record ThemeAdmissionSummary(
            Map<String, Long> shadowActionCounts,
            long wouldWriteCandidateCount,
            long wouldWriteWatchlistCount,
            long wouldCreatePullbackPlanCount
    ) {
        public static ThemeAdmissionSummary empty() {
            return new ThemeAdmissionSummary(Map.of(), 0, 0, 0);
        }
    }

    public record TopNShadowBucket(
            String bucket,
            long sampleCount,
            BigDecimal avgActualReturn1d,
            BigDecimal winRate1d,
            BigDecimal avgActualReturn5d,
            BigDecimal winRate5d,
            BigDecimal avgActualReturn10d,
            BigDecimal winRate10d,
            BigDecimal avgMaxDrawdown10d,
            long missedByTop3Count
    ) {}

    public record PredictivePower(
            int n,
            BigDecimal lifecycleScoreCorrelation,
            BigDecimal continuationDaysCorrelation,
            BigDecimal breadthCorrelation,
            BigDecimal crowdingScoreCorrelation,
            BigDecimal topQuartileAvgReturn,
            BigDecimal bottomQuartileAvgReturn,
            BigDecimal topBottomSpread,
            String dataGapReason
    ) {
        public static PredictivePower insufficient(int n, String reason) {
            return new PredictivePower(n, null, null, null, null, null, null, null, reason);
        }
    }

    public record CalibrationFinding(
            String code,
            String severity,
            String message
    ) {}
}
