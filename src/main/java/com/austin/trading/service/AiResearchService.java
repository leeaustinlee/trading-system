package com.austin.trading.service;

import com.austin.trading.client.AiClaudeClient;
import com.austin.trading.client.AiClaudeClient.AiResponse;
import com.austin.trading.client.AiCodexClient;
import com.austin.trading.dto.response.AiResearchResponse;
import com.austin.trading.entity.AiResearchLogEntity;
import com.austin.trading.repository.AiResearchLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * AI 研究業務邏輯層。
 * <p>
 * 組合：Prompt 建構 → Claude API 呼叫 → 結果落表 → 可選寫檔
 * </p>
 * <p>
 * 研究類型常數（{@code researchType}）：
 * <ul>
 *   <li>PREMARKET   - 08:30 盤前分析</li>
 *   <li>STOCK_EVAL  - 個股深度研究</li>
 *   <li>FINAL_DECISION - 09:30 最終決策確認</li>
 *   <li>HOURLY_GATE - 整點行情閘研究</li>
 *   <li>POSTMARKET  - 15:30 盤後總結</li>
 * </ul>
 * </p>
 */
@Service
public class AiResearchService {

    private static final Logger log = LoggerFactory.getLogger(AiResearchService.class);

    private final AiClaudeClient         aiClaudeClient;
    private final AiCodexClient          aiCodexClient;
    private final AiResearchLogRepository repository;

    public AiResearchService(
            AiClaudeClient aiClaudeClient,
            AiCodexClient aiCodexClient,
            AiResearchLogRepository repository
    ) {
        this.aiClaudeClient = aiClaudeClient;
        this.aiCodexClient  = aiCodexClient;
        this.repository     = repository;
    }

    /**
     * 執行 AI 研究並儲存。
     *
     * @param tradingDate    交易日
     * @param researchType   研究類型
     * @param symbol         個股代號（市場研究傳 null）
     * @param userPrompt     使用者提示詞
     * @param systemContext  系統說明（可為 null）
     * @param publishTitle   發布給 Codex 的標題（null 表示不發布）
     * @return 儲存後的研究記錄
     */
    public AiResearchResponse research(
            LocalDate tradingDate,
            String researchType,
            String symbol,
            String userPrompt,
            String systemContext,
            String publishTitle
    ) {
        log.info("[AiResearchService] Start research type={} symbol={}", researchType, symbol);

        // 呼叫 Claude API
        AiResponse aiResponse = aiClaudeClient.sendMessage(userPrompt, systemContext);

        // 儲存
        AiResearchLogEntity entity = new AiResearchLogEntity();
        entity.setTradingDate(tradingDate);
        entity.setResearchType(researchType);
        entity.setSymbol(symbol);
        entity.setPromptSummary(truncate(userPrompt, 480));

        if (aiResponse != null) {
            entity.setResearchResult(aiResponse.content());
            entity.setModel(aiResponse.model());
            entity.setTokensUsed(aiResponse.totalTokens());
        } else {
            entity.setResearchResult("（AI 未啟用或呼叫失敗）");
            entity.setModel("N/A");
        }

        AiResearchLogEntity saved = repository.save(entity);
        log.info("[AiResearchService] Saved id={} type={}", saved.getId(), researchType);

        // 可選：寫入 claude-research-latest.md
        if (aiResponse != null && publishTitle != null && !publishTitle.isBlank()) {
            aiCodexClient.publishResearch(publishTitle, aiResponse.content(), symbol);
        }

        return toResponse(saved);
    }

    // ── 查詢方法 ─────────────────────────────────────────────────────────────────

    public List<AiResearchResponse> getByDate(LocalDate date) {
        return repository.findByTradingDateOrderByCreatedAtDesc(date)
                .stream().map(this::toResponse).toList();
    }

    public List<AiResearchResponse> getByDateAndType(LocalDate date, String type) {
        return repository.findByTradingDateAndResearchTypeOrderByCreatedAtDesc(date, type)
                .stream().map(this::toResponse).toList();
    }

    public Optional<AiResearchResponse> getLatestForSymbol(String type, String symbol) {
        return repository.findTopByResearchTypeAndSymbolOrderByCreatedAtDesc(type, symbol)
                .map(this::toResponse);
    }

    public Optional<AiResearchResponse> getLatestForDate(LocalDate date, String type) {
        return repository.findTopByTradingDateAndResearchTypeOrderByCreatedAtDesc(date, type)
                .map(this::toResponse);
    }

    // ── 私有 ─────────────────────────────────────────────────────────────────────

    private AiResearchResponse toResponse(AiResearchLogEntity entity) {
        return new AiResearchResponse(
                entity.getId(),
                entity.getTradingDate(),
                entity.getResearchType(),
                entity.getSymbol(),
                entity.getPromptSummary(),
                entity.getResearchResult(),
                entity.getModel(),
                entity.getTokensUsed(),
                entity.getCreatedAt()
        );
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}
