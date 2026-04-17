package com.austin.trading.notify;

import com.austin.trading.config.LineNotifyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * LINE Notify 發送器。
 * trading.line.enabled=true 且 token 不為空時才實際發送。
 * 否則僅 log，不報錯。
 */
@Component
public class LineSender {

    private static final Logger log = LoggerFactory.getLogger(LineSender.class);
    private static final String LINE_NOTIFY_URL = "https://notify-api.line.me/api/notify";

    private final LineNotifyConfig config;
    private final WebClient webClient;

    public LineSender(LineNotifyConfig config, WebClient.Builder webClientBuilder) {
        this.config = config;
        this.webClient = webClientBuilder.baseUrl(LINE_NOTIFY_URL).build();
    }

    /**
     * 發送 LINE 通知。
     * @param message 要發送的文字（最多 1000 字元，超出自動截斷）
     */
    public boolean send(String message) {
        if (!config.isEnabled()) {
            log.debug("[LineSender] LINE disabled, skip: {}", abbreviate(message));
            return false;
        }
        String token = config.getToken();
        if (token == null || token.isBlank()) {
            log.warn("[LineSender] LINE token not set, skip sending.");
            return false;
        }

        String truncated = message.length() > 1000 ? message.substring(0, 997) + "..." : message;

        try {
            webClient.post()
                    .header("Authorization", "Bearer " + token)
                    .body(BodyInserters.fromFormData("message", truncated))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("[LineSender] Sent: {}", abbreviate(message));
            return true;
        } catch (Exception e) {
            log.error("[LineSender] Failed to send LINE message: {}", e.getMessage());
            return false;
        }
    }

    private String abbreviate(String s) {
        return s == null ? "" : s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }
}
