package com.austin.trading.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only lifecycle annotations for existing backend rows.
 *
 * <p>These diagnostics are advisory labels only. They must not write source
 * tables, alter ranking scores, or affect BUY/SELL/final decision paths.</p>
 */
public record ThemeLifecycleAnnotationResponse(
        boolean annotationOnly,
        boolean advisoryOnly,
        boolean doesNotAffectBuySell,
        LocalDate requestedDate,
        String targetType,
        List<Item> items,
        List<String> dataGaps
) {
    public static ThemeLifecycleAnnotationResponse of(LocalDate requestedDate,
                                                      String targetType,
                                                      List<Item> items,
                                                      List<String> dataGaps) {
        return new ThemeLifecycleAnnotationResponse(
                true, true, true, requestedDate, targetType,
                List.copyOf(items), List.copyOf(dataGaps));
    }

    public record Item(
            String symbol,
            String stockName,
            String themeTag,
            LocalDate sourceDate,
            LocalDate tradingDate,
            String sourceType,
            String sourceStatus,
            Integer rankingRank,
            BigDecimal positionQty,
            BigDecimal positionAvgCost,
            String positionStatus,
            String stage,
            String previousStage,
            boolean stageChanged,
            BigDecimal stageConfidence,
            BigDecimal lifecycleScore,
            Integer breadth,
            Integer leaderCount,
            Integer continuationDays,
            BigDecimal rotationScore,
            BigDecimal crowdingScore,
            String recommendedPlaybookJson,
            String avoidPlaybookJson,
            String advisoryAction,
            boolean annotationOnly,
            boolean doesNotAffectBuySell,
            String dataGapReason
    ) {}
}
