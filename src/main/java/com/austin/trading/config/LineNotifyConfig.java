package com.austin.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trading.line")
public class LineNotifyConfig {

    private boolean enabled = false;
    private String token = "";
    private String channelAccessToken = "";
    private String to = "";
    private String pushUrl = "https://api.line.me/v2/bot/message/push";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getChannelAccessToken() { return channelAccessToken; }
    public void setChannelAccessToken(String channelAccessToken) { this.channelAccessToken = channelAccessToken; }
    public String getTo() { return to; }
    public void setTo(String to) { this.to = to; }
    public String getPushUrl() { return pushUrl; }
    public void setPushUrl(String pushUrl) { this.pushUrl = pushUrl; }

    public String resolveAccessToken() {
        if (channelAccessToken != null && !channelAccessToken.isBlank()) {
            return channelAccessToken;
        }
        return token;
    }
}
