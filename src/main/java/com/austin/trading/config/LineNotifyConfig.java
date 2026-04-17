package com.austin.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trading.line")
public class LineNotifyConfig {

    private boolean enabled = false;
    private String token = "";
    private String notifyUrl = "https://notify-api.line.me/api/notify";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getNotifyUrl() { return notifyUrl; }
    public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }
}
