package com.austin.trading.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeOpsAndAiTrustConfigTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    @Test
    void localProfileEnablesDailyThemeOpsBuild() throws IOException {
        String yaml = Files.readString(RESOURCES.resolve("application-local.yml"), StandardCharsets.UTF_8);

        assertThat(yaml).contains("theme-ops-daily-build:");
        assertThat(yaml).containsPattern("theme-ops-daily-build:\\s*\\R\\s*enabled: true");
    }

    @Test
    void marketCommandLabelsAdvancedThemeOpsGapSeparatelyFromDailyCandidateScan() throws IOException {
        String mobile = Files.readString(RESOURCES.resolve("static/mobile.html"), StandardCharsets.UTF_8);
        String desktop = Files.readString(RESOURCES.resolve("static/index.html"), StandardCharsets.UTF_8);

        assertThat(mobile).contains("AI 可用 / 進階題材研究未更新");
        assertThat(desktop).contains("AI 可用 / 進階題材研究未更新");
    }
}
