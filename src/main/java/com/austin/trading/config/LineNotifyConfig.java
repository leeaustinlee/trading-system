package com.austin.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "trading.line")
public class LineNotifyConfig {

    private boolean enabled = false;
    private String token = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
}
