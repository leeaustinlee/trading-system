package com.austin.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Anthropic Claude API 設定。
 * <p>
 * 在 application.yml 中設定：
 * <pre>
 * trading:
 *   ai:
 *     claude:
 *       enabled: true
 *       api-key: "sk-ant-xxxxx"
 *       model: "claude-sonnet-4-6"
 *       max-tokens: 4096
 *       research-output-path: "/mnt/d/ai/stock/claude-research-latest.md"
 * </pre>
 * </p>
 */
@Configuration
@ConfigurationProperties(prefix = "trading.ai.claude")
public class AiClaudeConfig {

    private boolean enabled = false;
    private String apiKey = "";
    private String model = "claude-sonnet-4-6";
    private int maxTokens = 4096;

    /** 研究輸出的 Markdown 檔案路徑（選填，空字串表示不寫檔）*/
    private String researchOutputPath = "";

    /** Claude Code 研究請求 JSON 路徑（無 API Key 模式：Java 寫請求，Claude Code 排程讀取並分析）*/
    private String requestOutputPath = "";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public String getResearchOutputPath() { return researchOutputPath; }
    public void setResearchOutputPath(String researchOutputPath) {
        this.researchOutputPath = researchOutputPath;
    }

    public String getRequestOutputPath() { return requestOutputPath; }
    public void setRequestOutputPath(String requestOutputPath) {
        this.requestOutputPath = requestOutputPath;
    }
}
