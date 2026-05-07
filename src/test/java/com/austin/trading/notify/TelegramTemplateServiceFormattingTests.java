package com.austin.trading.notify;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramTemplateServiceFormattingTests {

    @Test
    void wrapHtmlRemovesDuplicateHeadlineFromBody() {
        String html = TelegramTemplateService.wrapHtml(
                "🧭 明日策略",
                "🧭 明日策略\r\n\r\n📊 市場\n- 觀察\n\n📌 結論\n- 人工確認"
        );

        assertThat(html).startsWith("<b>🧭 明日策略</b>");
        assertThat(html).contains("📊 市場");
        assertThat(html).contains("📌 結論");
        assertThat(html).doesNotContain("🧭 明日策略\n\n🧭 明日策略");
    }
}
