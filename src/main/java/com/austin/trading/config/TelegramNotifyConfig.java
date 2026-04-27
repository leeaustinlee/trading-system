package com.austin.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Telegram Bot 通知設定。Token / chat id 由環境變數注入，不寫死於程式。
 *
 * <h3>環境變數</h3>
 * <ul>
 *   <li>{@code TELEGRAM_ENABLED}（預設 false / prod 預設 true）</li>
 *   <li>{@code TELEGRAM_BOT_TOKEN}</li>
 *   <li>{@code TELEGRAM_CHAT_ID}</li>
 *   <li>{@code TELEGRAM_PARSE_MODE}（HTML / Markdown / 空白；預設 HTML）</li>
 *   <li>{@code TELEGRAM_TIMEOUT_SECONDS}（HTTP timeout；預設 10）</li>
 *   <li>{@code TELEGRAM_MAX_SEGMENT_LENGTH}（單則訊息切段長度；預設 3500，Telegram 上限 4096）</li>
 *   <li>{@code TELEGRAM_API_BASE}（預設 https://api.telegram.org）</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "trading.telegram")
public class TelegramNotifyConfig {

    private boolean enabled = false;
    private String botToken = "";
    private String chatId = "";
    private String parseMode = "HTML";
    private int timeoutSeconds = 10;
    private int maxSegmentLength = 3500;
    private String apiBase = "https://api.telegram.org";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBotToken() { return botToken; }
    public void setBotToken(String botToken) { this.botToken = botToken; }
    public String getChatId() { return chatId; }
    public void setChatId(String chatId) { this.chatId = chatId; }
    public String getParseMode() { return parseMode; }
    public void setParseMode(String parseMode) { this.parseMode = parseMode; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getMaxSegmentLength() { return maxSegmentLength; }
    public void setMaxSegmentLength(int maxSegmentLength) { this.maxSegmentLength = maxSegmentLength; }
    public String getApiBase() { return apiBase; }
    public void setApiBase(String apiBase) { this.apiBase = apiBase; }

    /** 檢查 token / chat id 是否有效（非空白）。 */
    public boolean hasCredentials() {
        return botToken != null && !botToken.isBlank()
                && chatId != null && !chatId.isBlank();
    }

    /** 組 sendMessage URL：{apiBase}/bot{token}/sendMessage */
    public String resolveSendMessageUrl() {
        String base = apiBase == null || apiBase.isBlank() ? "https://api.telegram.org" : apiBase.trim();
        return base + "/bot" + (botToken == null ? "" : botToken.trim()) + "/sendMessage";
    }
}
