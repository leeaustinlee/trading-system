package com.austin.trading.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DesktopThemeOpsNavTest {

    @Test
    void desktopShellLinksAdvancedMenuToReadOnlyThemeOpsDashboard() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"));

        assertThat(html).contains("id=\"navThemeOpsBtn\"", "題材 Ops");
        assertThat(html).contains("/dashboard/theme-first?date=", "window.location.href");
        assertThat(html).doesNotContain("navThemeOpsBtn\" data-page=\"");
    }
}
