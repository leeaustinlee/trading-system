package com.austin.trading.notify;

import com.austin.trading.config.TelegramNotifyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v2.13 Telegram 通知 sender 單元測試（MVP）。
 *
 * <p>不直接打外部 API；驗證：</p>
 * <ul>
 *   <li>disabled / token 缺失 → 不送、不丟例外</li>
 *   <li>HTML escape 工作正確</li>
 *   <li>長訊息切段</li>
 * </ul>
 */
class TelegramSenderTests {

    private TelegramNotifyConfig cfg;
    private TelegramSender sender;

    @BeforeEach
    void setUp() {
        cfg = new TelegramNotifyConfig();
        sender = new TelegramSender(cfg, WebClient.builder());
    }

    @Test
    void disabledFlag_skipsSend() {
        cfg.setEnabled(false);
        cfg.setBotToken("dummy");
        cfg.setChatId("dummy");
        assertThat(sender.send("hi")).isFalse();
    }

    @Test
    void missingToken_skipsSendAndReturnsFalse() {
        cfg.setEnabled(true);
        cfg.setBotToken("");
        cfg.setChatId("123");
        assertThat(sender.send("hi")).isFalse();
    }

    @Test
    void missingChatId_skipsSendAndReturnsFalse() {
        cfg.setEnabled(true);
        cfg.setBotToken("dummy");
        cfg.setChatId("");
        assertThat(sender.send("hi")).isFalse();
    }

    @Test
    void blankMessage_skipsSend() {
        cfg.setEnabled(true);
        cfg.setBotToken("dummy");
        cfg.setChatId("123");
        assertThat(sender.send("")).isFalse();
        assertThat(sender.send("   ")).isFalse();
    }

    @Test
    void htmlEscape_replacesAllCriticalChars() {
        assertThat(TelegramSender.escapeHtml("a & b < c > d")).isEqualTo("a &amp; b &lt; c &gt; d");
        assertThat(TelegramSender.escapeHtml(null)).isEmpty();
        assertThat(TelegramSender.escapeHtml("<b>hi</b>")).isEqualTo("&lt;b&gt;hi&lt;/b&gt;");
    }

    @Test
    void splitForTelegram_shortText_oneSegment() {
        List<String> out = TelegramSender.splitForTelegram("hello", 3500);
        assertThat(out).containsExactly("hello");
    }

    @Test
    void splitForTelegram_longText_multipleSegments() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("第 ").append(i).append(" 段：").append("a".repeat(800)).append("\n\n");
        }
        List<String> out = TelegramSender.splitForTelegram(sb.toString(), 3500);
        assertThat(out.size()).isGreaterThan(1);
        out.forEach(seg -> assertThat(seg.length()).isLessThanOrEqualTo(3500));
    }

    @Test
    void splitForTelegram_singleHugeChunk_hardCut() {
        String huge = "x".repeat(10_000);
        List<String> out = TelegramSender.splitForTelegram(huge, 3500);
        assertThat(out).hasSize(3);
        out.forEach(seg -> assertThat(seg.length()).isLessThanOrEqualTo(3500));
    }

    @Test
    void resolveSendMessageUrl_concatCorrectly() {
        cfg.setApiBase("https://api.telegram.org");
        cfg.setBotToken("ABC123");
        assertThat(cfg.resolveSendMessageUrl()).isEqualTo("https://api.telegram.org/botABC123/sendMessage");
    }

    @Test
    void hasCredentials_falseWhenAnyMissing() {
        assertThat(cfg.hasCredentials()).isFalse();
        cfg.setBotToken("a");
        assertThat(cfg.hasCredentials()).isFalse();
        cfg.setChatId("b");
        assertThat(cfg.hasCredentials()).isTrue();
    }
}
