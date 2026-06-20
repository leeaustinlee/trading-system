package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * P3-E read-only lifecycle pullback plan shadow response.
 *
 * <p>Safety contract: this report only explains potential pullback-watch plans for
 * strong/near-limit theme leaders. It must not create candidates/watchlists, place
 * orders, mutate positions, or affect ranking/risk/BUY/SELL paths.</p>
 */
public record LifecyclePullbackPlanShadowResponse(
        boolean readOnly,
        boolean shadowOnly,
        boolean doesNotAffectBuySell,
        boolean doesNotWriteCandidateWatchlist,
        boolean doesNotAffectRanking,
        int requestedDays,
        LocalDate startDate,
        LocalDate endDate,
        long totalRows,
        long pullbackPlanRows,
        long avoidChasingRows,
        long watchPullbackRows,
        long waitSupportRows,
        BigDecimal averageReturn5d,
        BigDecimal averageReturn10d,
        BigDecimal averageMaxDrawdown,
        List<StatusSummary> byStatus,
        List<StageSummary> byLifecycleStage,
        List<Item> rows,
        List<String> dataGaps
) {
    public static LifecyclePullbackPlanShadowResponse of(int requestedDays,
                                                         LocalDate startDate,
                                                         LocalDate endDate,
                                                         long totalRows,
                                                         long pullbackPlanRows,
                                                         long avoidChasingRows,
                                                         long watchPullbackRows,
                                                         long waitSupportRows,
                                                         BigDecimal averageReturn5d,
                                                         BigDecimal averageReturn10d,
                                                         BigDecimal averageMaxDrawdown,
                                                         List<StatusSummary> byStatus,
                                                         List<StageSummary> byLifecycleStage,
                                                         List<Item> rows,
                                                         List<String> dataGaps) {
        return new LifecyclePullbackPlanShadowResponse(
                true,
                true,
                true,
                true,
                true,
                requestedDays,
                startDate,
                endDate,
                totalRows,
                pullbackPlanRows,
                avoidChasingRows,
                watchPullbackRows,
                waitSupportRows,
                averageReturn5d,
                averageReturn10d,
                averageMaxDrawdown,
                List.copyOf(byStatus),
                List.copyOf(byLifecycleStage),
                List.copyOf(rows),
                List.copyOf(dataGaps));
    }

    public record StatusSummary(
            String planStatus,
            long count,
            BigDecimal averageReturn5d,
            BigDecimal averageReturn10d,
            BigDecimal averageMaxDrawdown
    ) {}

    public record StageSummary(
            String lifecycleStage,
            long count,
            long pullbackPlanRows,
            long nearLimitRows,
            BigDecimal averageLimitUpDensity,
            BigDecimal averageReturn5d,
            BigDecimal averageMaxDrawdown
    ) {}

    public record Item(
            LocalDate tradingDate,
            String symbol,
            String stockName,
            String themeTag,
            String lifecycleStage,
            BigDecimal lifecycleScore,
            Integer continuationDays,
            Integer breadth,
            Integer leaderCount,
            BigDecimal crowdingScore,
            BigDecimal limitUpDensity,
            Boolean nearLimit,
            String limitRisk,
            Boolean wouldCreatePullbackPlan,
            Boolean wouldWriteCandidate,
            Boolean wouldWriteWatchlist,
            String shadowAction,
            String planStatus,
            String planReason,
            BigDecimal actualReturn5d,
            BigDecimal actualReturn10d,
            BigDecimal maxDrawdownPct,
            String traceStatus
    ) {}
}
