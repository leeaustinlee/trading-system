package com.austin.trading.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MobileThemeOpsTabTest {

    @Test
    void mobileShellContainsThemeOpsTabAndReadOnlyApiFetches() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/mobile.html"));

        assertThat(html).contains("data-r=\"themeops\"", "href=\"#/themeops\"", "<span>題材</span>");
        assertThat(html).contains("Views.themeops", "fetchThemeOpsTab", "Theme-first Ops / Research");
        assertThat(html).contains("/api/dashboard/theme-first?date=", "/api/hot-groups/radar?date=",
                "/api/promotion-review/queue?date=", "/api/themes/lifecycle?date=",
                "/api/research-universe?date=");
    }

    @Test
    void mobileThemeOpsTabIsReadOnlyAndServiceWorkerCacheIsBumped() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/mobile.html"));
        String sw = Files.readString(Path.of("src/main/resources/static/sw.js"));

        assertThat(html).contains("Read-only：只看題材雷達");
        assertThat(html).doesNotContain("method=\"post\"", "method='post'", "approve button", "reject button");
        assertThat(sw).contains("tt-v6-2026-05-24-themeops-tab");
    }
}
