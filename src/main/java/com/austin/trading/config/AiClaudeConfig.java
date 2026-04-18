package com.austin.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Claude Code Agent 檔案路徑設定（無直接 API 模式）。
 * <p>
 * Java 寫出研究請求 JSON → Claude Code Agent 分析 → 寫回 Markdown → Java 讀取落表。
 * </p>
 */
@Configuration
@ConfigurationProperties(prefix = "trading.ai.claude")
public class AiClaudeConfig {

    /** Claude Code Agent 寫入研究結果的 Markdown 路徑 */
    private String researchOutputPath = "";

    /** Java 排程寫出研究請求 JSON 的路徑，供 Claude Code Agent 讀取 */
    private String requestOutputPath = "";

    public String getResearchOutputPath() { return researchOutputPath; }
    public void setResearchOutputPath(String researchOutputPath) {
        this.researchOutputPath = researchOutputPath;
    }

    public String getRequestOutputPath() { return requestOutputPath; }
    public void setRequestOutputPath(String requestOutputPath) {
        this.requestOutputPath = requestOutputPath;
    }
}
