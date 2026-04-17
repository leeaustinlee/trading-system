package com.austin.trading.notify;

import com.austin.trading.config.LineNotifyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

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

        try {
            webClient.post()
                    .uri(pushUrl.trim())
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
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
