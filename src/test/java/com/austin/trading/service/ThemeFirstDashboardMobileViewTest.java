package com.austin.trading.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeFirstDashboardMobileViewTest {

    @Test
    void htmlContainsMobileViewportStickyDateCardsAndResponsiveCss() {
        ThemeFirstDashboardService service = new ThemeFirstDashboardService();
        String html = service.renderHtml(LocalDate.of(2026, 5, 25));

        assertThat(html).contains("<meta name=\"viewport\"", "class=\"topbar sticky-date-selector\"",
                "mobile-card", "@media (max-width: 760px)", "min-height:44px", "font-size:14px");
        assertThat(html).contains("Top Status", "Safety Metrics", "Hot Group Radar", "Promotion Review Queue",
                "Lifecycle", "Candidate / Decision", "Build Trace");
        assertThat(html).contains("<details", "<summary");
    }

    @Test
    void htmlDoesNotExposeTradingOrReviewDecisionControls() {
        ThemeFirstDashboardService service = new ThemeFirstDashboardService();
        String html = service.renderHtml(LocalDate.of(2026, 5, 25)).toUpperCase();

        assertThat(html).doesNotContain("BUY", "SELL", "ENTER", "APPROVE", "REJECT", "FORM METHOD=\"POST\"");
    }
}
