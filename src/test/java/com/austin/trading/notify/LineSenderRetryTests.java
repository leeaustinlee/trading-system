package com.austin.trading.notify;

import com.austin.trading.config.LineNotifyConfig;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 驗證 LineSender 收到 429 後會重試一次再 graceful fail。
 *
 * 用 JDK 內建 {@link HttpServer}（不需要額外 mockwebserver 依賴）。
 * Test 內透過反射把 RETRY_DELAY_MS 改為 50ms，避免每個 case 等 5 秒。
 */
class LineSenderRetryTests {

    private HttpServer server;
    private int port;
    private final Deque<Integer> queuedStatuses = new ArrayDeque<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private LineSender sender;

    @BeforeEach
    void setUp() throws Exception {
        // 把 retry delay 改成 50ms 以加快 test（LineSender.RETRY_DELAY_MS 是 package-private 非 final）
        LineSender.RETRY_DELAY_MS = 50L;

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        HttpHandler handler = exchange -> {
            requestCount.incrementAndGet();
            int code;
            synchronized (queuedStatuses) {
                code = queuedStatuses.isEmpty() ? 200 : queuedStatuses.pollFirst();
            }
            byte[] body = "{}".getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        };
        server.createContext("/v2/bot/message/push", handler);
        server.start();

        LineNotifyConfig cfg = new LineNotifyConfig();
        cfg.setEnabled(true);
        cfg.setChannelAccessToken("dummy");
        cfg.setTo("U_dummy");
        cfg.setPushUrl("http://127.0.0.1:" + port + "/v2/bot/message/push");

        sender = new LineSender(cfg, WebClient.builder());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        LineSender.RETRY_DELAY_MS = 5_000L; // restore default
    }

    private void enqueue(int... statuses) {
        synchronized (queuedStatuses) {
            for (int s : statuses) queuedStatuses.addLast(s);
        }
    }

    @Test
    void firstAttempt200_returnsTrue_noRetry() {
        enqueue(200);

        boolean ok = sender.send("hello");

        assertThat(ok).isTrue();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void first429_then200_retriesOnce_returnsTrue() {
        enqueue(429, 200);

        boolean ok = sender.send("retry-me");

        assertThat(ok).isTrue();
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void twoConsecutive429_givesUp_returnsFalse() {
        enqueue(429, 429);

        boolean ok = sender.send("rate-limited");

        assertThat(ok).isFalse();
        assertThat(requestCount.get()).isEqualTo(2); // 1 + 1 retry
    }

    @Test
    void non429_doesNotRetry() {
        enqueue(500);

        boolean ok = sender.send("server-error");

        assertThat(ok).isFalse();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void disabled_skips() {
        LineNotifyConfig cfg = new LineNotifyConfig();
        cfg.setEnabled(false);
        LineSender disabled = new LineSender(cfg, WebClient.builder());

        boolean ok = disabled.send("noop");

        assertThat(ok).isFalse();
        assertThat(requestCount.get()).isZero();
    }
}
