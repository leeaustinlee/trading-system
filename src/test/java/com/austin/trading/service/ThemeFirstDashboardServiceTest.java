package com.austin.trading.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeFirstDashboardServiceTest {

    @Test
    void metadataIsReadOnlyAndListsExistingApiSources() {
        ThemeFirstDashboardService service = new ThemeFirstDashboardService();
        Map<String, Object> metadata = service.readOnlyMetadata(LocalDate.of(2026, 5, 25));

        assertThat(metadata).containsEntry("readOnly", true)
                .containsEntry("doesNotWriteCandidateStock", true)
                .containsEntry("doesNotWriteFinalDecision", true)
                .containsEntry("doesNotWriteProductionScore", true)
                .containsEntry("doesNotAffectBuySellEnter", true)
                .containsEntry("doesNotAffectFinalDecisionEngine", true)
                .containsEntry("noAutoPromotion", true);
        assertThat((List<String>) metadata.get("apiSources"))
                .contains("/api/ops/daily-summary?date=", "/api/promotion-review/queue?date=", "/api/decisions/current");
    }

    @Test
    void htmlUsesOnlyGetFetchesAndNoWriteActions() {
        ThemeFirstDashboardService service = new ThemeFirstDashboardService();
        String html = service.renderHtml(LocalDate.of(2026, 5, 25));

        assertThat(html).contains("fetch(", "/api/ops/daily-summary?date=", "/api/hot-groups/radar?date=");
        assertThat(html).doesNotContain("method=\"post\"", "method='post'", "fetchPost", "POST /api");
        assertThat(html).doesNotContain("candidate_stock", "final_decision", "production score");
    }
}
