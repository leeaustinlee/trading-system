package com.austin.trading.notify;

import com.austin.trading.config.LineNotifyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * LINE Push API 發送器。
 * trading.line.enabled=true 且 access token / to 不為空時才實際發送。
 * 否則僅 log，不報錯。
 */
@Component
public class LineSender {

    private static final Logger log = LoggerFactory.getLogger(LineSender.class);

    /** 429 後等待多久重試一次。沒有 Retry-After 時用此值。<b>package-private、非 final，方便 test 改快。</b> */
    static long RETRY_DELAY_MS = 5_000L;
    /** 收到 429 後最多再試幾次（=1 表示總共最多送 2 次）。 */
    private static final int MAX_RETRY_ON_429 = 1;

    private final LineNotifyConfig config;
    private final WebClient webClient;

    public LineSender(LineNotifyConfig config, WebClient.Builder webClientBuilder) {
        this.config = config;
        this.webClient = webClientBuilder.build();
    }

    /**
     * 發送 LINE 通知。
     * @param message 要發送的文字（最多 5000 字元，超出自動截斷）
     */
    public boolean send(String message) {
        if (!config.isEnabled()) {
            log.debug("[LineSender] LINE disabled, skip: {}", abbreviate(message));
            return false;
        }
        String token = config.resolveAccessToken();
        if (token == null || token.isBlank()) {
            log.warn("[LineSender] LINE channel access token not set, skip sending.");
            return false;
        }
        String to = config.getTo();
        if (to == null || to.isBlank()) {
            log.warn("[LineSender] LINE to not set, skip sending.");
            return false;
        }
        String pushUrl = config.getPushUrl();
        if (pushUrl == null || pushUrl.isBlank()) {
            log.warn("[LineSender] LINE pushUrl not set, skip sending.");
            return false;
        }

        String truncated = message.length() > 5000 ? message.substring(0, 4997) + "..." : message;
        Map<String, Object> payload = Map.of(
                "to", to.trim(),
                "messages", List.of(Map.of("type", "text", "text", truncated))
        );

        return doSendWithRetry(pushUrl.trim(), token, payload, message, 0);
    }

    /**
     * 實際送出，遇 429 最多再試 {@link #MAX_RETRY_ON_429} 次（5 秒間隔），其他錯誤直接 graceful fail。
     */
    private boolean doSendWithRetry(String pushUrl, String token, Map<String, Object> payload,
                                    String originalMessage, int attempt) {
        try {
            webClient.post()
                    .uri(pushUrl)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            if (attempt > 0) {
                log.info("[LineSender] Sent after {} retry: {}", attempt, abbreviate(originalMessage));
            } else {
                log.info("[LineSender] Sent: {}", abbreviate(originalMessage));
            }
            return true;
        } catch (WebClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            if (status.value() == 429 && attempt < MAX_RETRY_ON_429) {
                log.warn("[LineSender] 429 Too Many Requests (attempt {}), backoff {}ms then retry once.",
                        attempt + 1, RETRY_DELAY_MS);
                try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return doSendWithRetry(pushUrl, token, payload, originalMessage, attempt + 1);
            }
            log.error("[LineSender] Failed to send LINE message: {} (giving up after {} attempt(s))",
                    e.getMessage(), attempt + 1);
            return false;
        } catch (Exception e) {
            log.error("[LineSender] Failed to send LINE message: {}", e.getMessage());
            return false;
        }
    }

    private String abbreviate(String s) {
        return s == null ? "" : s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }
}
