package com.austin.trading.dto.response;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Read-only manual-review queue for unresolved theme taxonomy rows.
 *
 * This response is an operator aid only: it does not approve, mutate, or apply
 * any stock_theme_mapping category changes.
 */
public record ThemeManualReviewQueueResponse(
        long totalReviewItems,
        long filteredReviewItems,
        int returnedItems,
        Map<String, Long> byReviewPriority,
        Map<String, Long> byRecommendedAction,
        List<String> categoryOptions,
        List<String> reviewInstructions,
        List<String> evidenceChecklist,
        Map<String, String> reviewDecisionSchema,
        String safetyNote,
        LocalDateTime generatedAt,
        List<ThemeOtherCategorySuggestionResponse> items
) {
}
