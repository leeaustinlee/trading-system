package com.austin.trading.service;

import com.austin.trading.client.AiClaudeClient;
import com.austin.trading.client.AiClaudeClient.AiResponse;
import com.austin.trading.client.AiCodexClient;
import com.austin.trading.config.AiClaudeConfig;
import com.austin.trading.dto.response.AiResearchResponse;
import com.austin.trading.entity.AiResearchLogEntity;
import com.austin.trading.repository.AiResearchLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final AiClaudeConfig         aiClaudeConfig;

    public AiResearchService(
            AiClaudeClient aiClaudeClient,
            AiCodexClient aiCodexClient,
            AiResearchLogRepository repository,
            AiClaudeConfig aiClaudeConfig
    ) {
        this.aiClaudeClient  = aiClaudeClient;
        this.aiCodexClient   = aiCodexClient;
        this.repository      = repository;
        this.aiClaudeConfig  = aiClaudeConfig;
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

    // ── 檔案模式讀取（無 API Key 時，由 Claude Code 排程 Agent 寫入結果）──────────

    /**
     * 從 {@code trading.ai.claude.research-output-path} 讀取最新研究結果。
     * <p>
     * 適用於「無 API Key」模式：Claude Code 排程 Agent 分析完後寫入 Markdown 檔，
     * Java 側透過此方法讀回做後續處理（落表、UI 顯示）。
     * </p>
     *
     * @return 讀取成功時 Optional 含內容；路徑未設定或讀取失敗時回傳 empty
     */
    public Optional<String> readLatestFromFile() {
        String path = aiClaudeConfig.getResearchOutputPath();
        if (path == null || path.isBlank()) {
            log.debug("[AiResearchService] research-output-path not set, skip file read.");
            return Optional.empty();
        }
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p)) {
                log.debug("[AiResearchService] research file not found: {}", path);
                return Optional.empty();
            }
            String content = Files.readString(p);
            log.info("[AiResearchService] Read research from file, length={}", content.length());
            return Optional.of(content);
        } catch (IOException e) {
            log.warn("[AiResearchService] Failed to read research file {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 將 Claude Code 排程 Agent 寫入的研究結果落表（ai_research_log）。
     * <p>
     * 呼叫此方法前，Agent 應已將研究寫入 claude-research-latest.md。
     * Java 排程（如 PremarketNotifyJob）可在送出 LINE 通知前呼叫此方法，
     * 確保資料庫有留紀錄。
     * </p>
     *
     * @param tradingDate  交易日
     * @param researchType 研究類型（PREMARKET / POSTMARKET / STOCK_EVAL …）
     * @param symbol       個股代號（市場研究傳 null）
     * @return 儲存後的研究記錄；若讀檔失敗回傳 empty
     */
    public Optional<AiResearchResponse> importFromFile(
            LocalDate tradingDate, String researchType, String symbol) {

        return readLatestFromFile().map(content -> {
            AiResearchLogEntity entity = new AiResearchLogEntity();
            entity.setTradingDate(tradingDate);
            entity.setResearchType(researchType);
            entity.setSymbol(symbol);
            entity.setPromptSummary("(Claude Code 排程 Agent 寫入)");
            entity.setResearchResult(content);
            entity.setModel("claude-code-agent");
            entity.setTokensUsed(0);
            AiResearchLogEntity saved = repository.save(entity);
            log.info("[AiResearchService] Imported from file id={} type={}", saved.getId(), researchType);
            return toResponse(saved);
        });
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
