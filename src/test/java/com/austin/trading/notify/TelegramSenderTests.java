package com.austin.trading.notify;

import com.austin.trading.config.TelegramNotifyConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
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

        NotificationDeliveryResult result = sender.sendWithResult("hi");
        assertThat(result.provider()).isEqualTo("TELEGRAM");
        assertThat(result.status()).isEqualTo(NotificationDeliveryResult.STATUS_SKIPPED);
        assertThat(result.attempted()).isFalse();
        assertThat(result.delivered()).isFalse();
        assertThat(result.errorCode()).isEqualTo("DISABLED");
    }

    @Test
    void missingToken_skipsSendAndReturnsFalse() {
        cfg.setEnabled(true);
        cfg.setBotToken("");
        cfg.setChatId("123");
        assertThat(sender.send("hi")).isFalse();

        NotificationDeliveryResult result = sender.sendWithResult("hi");
        assertThat(result.status()).isEqualTo(NotificationDeliveryResult.STATUS_SKIPPED);
        assertThat(result.errorCode()).isEqualTo("MISSING_CREDENTIALS");
    }

    @Test
    void missingChatId_skipsSendAndReturnsFalse() {
        cfg.setEnabled(true);
        cfg.setBotToken("dummy");
        cfg.setChatId("");
        assertThat(sender.send("hi")).isFalse();
        assertThat(sender.sendWithResult("hi").errorCode()).isEqualTo("MISSING_CREDENTIALS");
    }

    @Test
    void blankMessage_skipsSend() {
        cfg.setEnabled(true);
        cfg.setBotToken("dummy");
        cfg.setChatId("123");
        assertThat(sender.send("")).isFalse();
        assertThat(sender.send("   ")).isFalse();
        assertThat(sender.sendWithResult("   ").errorCode()).isEqualTo("EMPTY_MESSAGE");
    }

    @Test
    void sendWithResult_http200_returnsDelivered() throws Exception {
        try (TestHttpServer http = TestHttpServer.start(200, "{\"ok\":true,\"result\":{\"message_id\":777}}")) {
            cfg.setEnabled(true);
            cfg.setBotToken("dummy");
            cfg.setChatId("123");
            cfg.setApiBase(http.baseUrl());

            NotificationDeliveryResult result = sender.sendWithResult("hello");

            assertThat(result.provider()).isEqualTo("TELEGRAM");
            assertThat(result.status()).isEqualTo(NotificationDeliveryResult.STATUS_DELIVERED);
            assertThat(result.attempted()).isTrue();
            assertThat(result.delivered()).isTrue();
            assertThat(result.httpStatus()).isEqualTo(200);
            assertThat(result.providerMessageId()).isEqualTo("777");
            assertThat(http.requestCount()).isEqualTo(1);
        }
    }

    @Test
    void sendWithResult_telegramOkFalse_isFailedTruth() throws Exception {
        try (TestHttpServer http = TestHttpServer.start(200, "{\"ok\":false,\"description\":\"bad parse\"}")) {
            cfg.setEnabled(true);
            cfg.setBotToken("dummy");
            cfg.setChatId("123");
            cfg.setApiBase(http.baseUrl());

            NotificationDeliveryResult result = sender.sendWithResult("hello");

            assertThat(result.status()).isEqualTo(NotificationDeliveryResult.STATUS_FAILED);
            assertThat(result.attempted()).isTrue();
            assertThat(result.delivered()).isFalse();
            assertThat(result.httpStatus()).isEqualTo(200);
            assertThat(result.errorCode()).isEqualTo("TELEGRAM_OK_FALSE");
        }
    }

    @Test
    void sendWithResult_multiSegment_recordsSegmentSummary() throws Exception {
        try (TestHttpServer http = TestHttpServer.start(200, "{\"ok\":true,\"result\":{\"message_id\":888}}")) {
            cfg.setEnabled(true);
            cfg.setBotToken("dummy");
            cfg.setChatId("123");
            cfg.setApiBase(http.baseUrl());
            cfg.setMaxSegmentLength(500);

            NotificationDeliveryResult result = sender.sendWithResult("x".repeat(1200));

            assertThat(result.status()).isEqualTo(NotificationDeliveryResult.STATUS_DELIVERED);
            assertThat(result.providerMessageId()).contains("segments=3", "delivered=3", "888");
            assertThat(http.requestCount()).isEqualTo(3);
        }
    }

    @Test
    void sendWithResult_http500_returnsFailed() throws Exception {
        try (TestHttpServer http = TestHttpServer.start(500, "boom")) {
            cfg.setEnabled(true);
            cfg.setBotToken("dummy");
            cfg.setChatId("123");
            cfg.setApiBase(http.baseUrl());

            NotificationDeliveryResult result = sender.sendWithResult("hello");

            assertThat(result.status()).isEqualTo(NotificationDeliveryResult.STATUS_FAILED);
            assertThat(result.attempted()).isTrue();
            assertThat(result.delivered()).isFalse();
            assertThat(result.httpStatus()).isEqualTo(500);
            assertThat(result.errorCode()).isEqualTo("HTTP_500");
            assertThat(result.errorBody()).contains("boom");
        }
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

    private static final class TestHttpServer implements AutoCloseable {
        private final HttpServer server;
        private int requestCount;

        private TestHttpServer(HttpServer server) {
            this.server = server;
        }

        static TestHttpServer start(int status, String body) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TestHttpServer wrapper = new TestHttpServer(server);
            server.createContext("/botdummy/sendMessage", exchange -> {
                wrapper.requestCount++;
                byte[] bytes = body.getBytes();
                exchange.sendResponseHeaders(status, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.start();
            return wrapper;
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        int requestCount() {
            return requestCount;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
