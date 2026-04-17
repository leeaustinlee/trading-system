package com.austin.trading.client;

import com.austin.trading.config.AiClaudeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Anthropic Claude Messages API 客戶端。
 * <p>
 * 配置項目（application.yml）：
 * <pre>
 *   trading.ai.claude.enabled: true
 *   trading.ai.claude.api-key: "sk-ant-xxxxx"
 *   trading.ai.claude.model: "claude-sonnet-4-6"
 * </pre>
 * </p>
 */
@Component
public class AiClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(AiClaudeClient.class);

    private static final String BASE_URL     = "https://api.anthropic.com";
    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String API_VERSION  = "2023-06-01";

    /**
     * AI 回覆結果
     *
     * @param content      回覆文字
     * @param model        實際使用的模型
     * @param inputTokens  輸入 token 數
     * @param outputTokens 輸出 token 數
     */
    public record AiResponse(
            String content,
            String model,
            int inputTokens,
            int outputTokens
    ) {
        public int totalTokens() { return inputTokens + outputTokens; }
    }

    private final AiClaudeConfig config;
    private final WebClient      webClient;
    private final ObjectMapper   objectMapper;

    public AiClaudeClient(
            AiClaudeConfig config,
            WebClient.Builder builder,
            ObjectMapper objectMapper
    ) {
        this.config       = config;
        this.objectMapper = objectMapper;
        this.webClient    = builder.baseUrl(BASE_URL)
                .defaultHeader("anthropic-version", API_VERSION)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * 傳送訊息給 Claude 並取得回覆。
     *
     * @param userPrompt 使用者提示詞
     * @param systemContext 系統角色說明（選填，空字串表示無 system prompt）
     * @return AI 回覆；若未啟用或呼叫失敗返回 null
     */
    public AiResponse sendMessage(String userPrompt, String systemContext) {
        if (!config.isEnabled()) {
            log.debug("[AiClaudeClient] disabled, skip.");
            return null;
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()) {
            log.warn("[AiClaudeClient] api-key not set.");
            return null;
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            return null;
        }

        try {
            String requestBody = buildRequestBody(userPrompt, systemContext);

            String responseJson = webClient.post()
                    .uri(MESSAGES_PATH)
                    .header("x-api-key", config.getApiKey())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(120))
                    .block();

            if (responseJson == null) return null;

            return parseResponse(responseJson);

        } catch (Exception e) {
            log.error("[AiClaudeClient] call failed: {}", e.getMessage());
            return null;
        }
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────────────

    private String buildRequestBody(String userPrompt, String systemContext) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", config.getModel());
        body.put("max_tokens", config.getMaxTokens());

        if (systemContext != null && !systemContext.isBlank()) {
            body.put("system", systemContext);
        }

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);

        return objectMapper.writeValueAsString(body);
    }

    private AiResponse parseResponse(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);

        // 取文字內容
        JsonNode contentArr = root.get("content");
        if (contentArr == null || !contentArr.isArray() || contentArr.isEmpty()) {
            log.warn("[AiClaudeClient] empty content in response");
            return null;
        }
        String text = contentArr.get(0).path("text").asText();

        // 模型與 token 用量
        String model = root.path("model").asText(config.getModel());
        JsonNode usage = root.get("usage");
        int inputTokens  = usage == null ? 0 : usage.path("input_tokens").asInt(0);
        int outputTokens = usage == null ? 0 : usage.path("output_tokens").asInt(0);

        log.info("[AiClaudeClient] tokens={}/{} model={}", inputTokens, outputTokens, model);
        return new AiResponse(text, model, inputTokens, outputTokens);
    }
}
