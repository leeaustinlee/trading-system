package com.austin.trading.dto.response;

import com.austin.trading.dto.internal.RankingTopNShadowResultDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only/shadow-only Top-N calibration response DTOs.
 *
 * <p>These records are intentionally explicit so the future UI can render Top3 vs
 * Top5/Top10/Top20 comparisons, missed winners, and theme quota shadow analysis
 * without inferring safety or aggregation semantics.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record RankingTopNShadowCalibrationResponse(
        boolean readOnly,
        boolean shadowOnly,
        boolean doesNotAffectRanking,
        boolean doesNotAffectBuySell,
        int requestedDays,
        LocalDate startDate,
        LocalDate endDate,
        long totalRows,
        long distinctTradingDays,
        TopNWindowComparison top3,
        TopNWindowComparison top5,
        TopNWindowComparison top10,
        TopNWindowComparison top20,
        List<TopNDeltaComparison> comparisons,
        List<String> dataGaps
) {
    public record TopNWindowComparison(
            int topN,
            long selectedCount,
            long distinctSymbols,
            BigDecimal averageRank,
            BigDecimal averageScore,
            BigDecimal averageReturn1d,
            BigDecimal winRate1d,
            BigDecimal averageReturn5d,
            BigDecimal winRate5d,
            BigDecimal averageReturn10d,
            BigDecimal winRate10d,
            BigDecimal averageMaxDrawdown10d,
            long missedWinnerCount,
            BigDecimal missedWinnerRate
    ) { }

    public record TopNDeltaComparison(
            String comparison,
            int baselineTopN,
            int candidateTopN,
            long incrementalRows,
            long incrementalMissedWinners,
            BigDecimal incrementalAverageReturn5d,
            BigDecimal incrementalAverageReturn10d,
            BigDecimal incrementalWinRate5d,
            BigDecimal incrementalWinRate10d
    ) { }

    public record MissedWinnersResponse(
            boolean readOnly,
            boolean shadowOnly,
            boolean doesNotAffectRanking,
            boolean doesNotAffectBuySell,
            int requestedDays,
            LocalDate startDate,
            LocalDate endDate,
            long totalMissedWinners,
            BigDecimal averageReturn5d,
            BigDecimal averageReturn10d,
            List<RankingTopNShadowResultDto> missedWinners,
            List<String> dataGaps
    ) { }

    public record ThemeQuotaResponse(
            boolean readOnly,
            boolean shadowOnly,
            boolean doesNotAffectRanking,
            boolean doesNotAffectBuySell,
            int requestedDays,
            LocalDate startDate,
            LocalDate endDate,
            long totalThemes,
            List<ThemeQuotaAnalysis> themes,
            List<String> dataGaps
    ) { }

    public record ThemeQuotaAnalysis(
            String themeTag,
            long totalRows,
            long top3Count,
            long top5Count,
            long top10Count,
            long top20Count,
            long outsideTop3Count,
            long missedWinnerCount,
            BigDecimal missedWinnerRateOutsideTop3,
            BigDecimal averageReturn5d,
            BigDecimal averageReturn10d,
            BigDecimal top3AverageReturn5d,
            BigDecimal outsideTop3AverageReturn5d,
            int shadowSuggestedTop3Quota,
            String shadowQuotaRationale
    ) { }
}
