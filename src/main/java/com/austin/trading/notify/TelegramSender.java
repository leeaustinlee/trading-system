package com.austin.trading.notify;

import com.austin.trading.config.TelegramNotifyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram Bot HTTP 發送器。
 *
 * <p>對應 {@code POST {apiBase}/bot{token}/sendMessage}，單則訊息超過
 * {@link TelegramNotifyConfig#getMaxSegmentLength()} 時自動切段。</p>
 *
 * <h3>失敗處理</h3>
 * <ul>
 *   <li>enabled=false 或 token / chat id 缺失 → 直接 return false 並 log</li>
 *   <li>HTTP 失敗（含 429 / 4xx / 5xx）→ log warn，不重試、不丟例外</li>
 *   <li>不會影響主交易流程</li>
 * </ul>
 */
@Component
public class TelegramSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramSender.class);

    private final TelegramNotifyConfig config;
    private final WebClient webClient;

    public TelegramSender(TelegramNotifyConfig config, WebClient.Builder webClientBuilder) {
        this.config = config;
        this.webClient = webClientBuilder.build();
    }

    /**
     * 發送 Telegram 通知。長訊息自動依 {@code maxSegmentLength} 切段為多則發送。
     *
     * @param message 訊息文字（若 parseMode=HTML，請事先 escape <、>、& 等字元；空白訊息會被略過）
     * @return 全部分段都成功才回 true；任一段失敗或被 skip 都回 false
     */
    public boolean send(String message) {
        if (!config.isEnabled()) {
            log.debug("[TelegramSender] disabled, skip: {}", abbreviate(message));
            return false;
        }
        if (!config.hasCredentials()) {
            log.warn("[TelegramSender] bot token / chat id 未設定，跳過發送。");
            return false;
        }
        if (message == null || message.isBlank()) {
            log.debug("[TelegramSender] empty message, skip");
            return false;
        }

        List<String> segments = splitForTelegram(message, Math.max(500, config.getMaxSegmentLength()));
        boolean allOk = true;
        for (int i = 0; i < segments.size(); i++) {
            String seg = segments.get(i);
            String prefix = segments.size() > 1 ? "(" + (i + 1) + "/" + segments.size() + ")\n" : "";
            boolean ok = doSend(prefix + seg);
            allOk = allOk && ok;
        }
        return allOk;
    }

    private boolean doSend(String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chat_id", config.getChatId().trim());
        payload.put("text", text);
        if (config.getParseMode() != null && !config.getParseMode().isBlank()) {
            payload.put("parse_mode", config.getParseMode().trim());
        }
        payload.put("disable_web_page_preview", true);
        try {
            webClient.post()
                    .uri(config.resolveSendMessageUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(Math.max(1, config.getTimeoutSeconds())));
            log.info("[TelegramSender] sent: {}", abbreviate(text));
            return true;
        } catch (WebClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            log.warn("[TelegramSender] HTTP {} send failed: {} body={}",
                    status.value(), e.getMessage(),
                    abbreviate(e.getResponseBodyAsString()));
            return false;
        } catch (Exception e) {
            log.warn("[TelegramSender] send failed: {}", e.getMessage());
            return false;
        }
    }

    /** 依 maxLen 把長訊息切段；先以雙換行斷段，避免破壞 HTML tag。 */
    static List<String> splitForTelegram(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        if (text.length() <= maxLen) {
            out.add(text);
            return out;
        }
        StringBuilder buf = new StringBuilder();
        for (String para : text.split("\n\n")) {
            String chunk = buf.length() == 0 ? para : "\n\n" + para;
            if (buf.length() + chunk.length() > maxLen && buf.length() > 0) {
                out.add(buf.toString());
                buf.setLength(0);
                chunk = para;
            }
            if (chunk.length() > maxLen) {
                // 單段超長：硬切
                if (buf.length() > 0) { out.add(buf.toString()); buf.setLength(0); }
                int idx = 0;
                while (idx < chunk.length()) {
                    int end = Math.min(idx + maxLen, chunk.length());
                    out.add(chunk.substring(idx, end));
                    idx = end;
                }
            } else {
                buf.append(chunk);
            }
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    /** 對 Telegram HTML parse mode 做 escape：<、>、& 必須轉義。 */
    public static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 77) + "..." : s;
    }
}
