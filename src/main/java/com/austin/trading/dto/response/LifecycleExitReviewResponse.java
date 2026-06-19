package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * P3-C backend lifecycle exit review response.
 *
 * <p>Safety contract: review rows are advisory shadow output only. They must not
 * affect BUY/SELL/final decision/execution paths, stops, positions, paper trades,
 * ranking production scores, or portfolio risk approval.</p>
 */
public record LifecycleExitReviewResponse(
        boolean reviewOnly,
        boolean advisoryOnly,
        boolean doesNotAffectBuySell,
        boolean autoSellEnabled,
        boolean stopMutationEnabled,
        boolean positionMutationEnabled,
        LocalDate requestedDate,
        int rowCount,
        int rebuiltCount,
        List<Item> rows,
        List<Item> items,
        List<String> dataGaps
) {
    public static LifecycleExitReviewResponse of(LocalDate requestedDate,
                                                 int rebuiltCount,
                                                 List<Item> items,
                                                 List<String> dataGaps) {
        List<Item> safeItems = List.copyOf(items);
        return new LifecycleExitReviewResponse(
                true,
                true,
                true,
                false,
                false,
                false,
                requestedDate,
                safeItems.size(),
                rebuiltCount,
                safeItems,
                safeItems,
                List.copyOf(dataGaps));
    }

    public record Item(
            Long id,
            LocalDate reviewDate,
            String symbol,
            String stockName,
            Long positionId,
            String themeTag,
            String lifecycleStage,
            String previousStage,
            BigDecimal lifecycleScore,
            Integer continuationDays,
            Integer breadth,
            Integer leaderCount,
            BigDecimal rotationScore,
            BigDecimal crowdingScore,
            String reviewAction,
            String reviewPriority,
            boolean reviewOnly,
            boolean autoSellEnabled,
            boolean stopMutationEnabled,
            boolean positionMutationEnabled,
            String sourcePositionStatus,
            String structuralExitTier,
            String priceState,
            String structureState,
            String dataGapReason,
            String reasonJson,
            LocalDateTime createdAt
    ) {}
}
