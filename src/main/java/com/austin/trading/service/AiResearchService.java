package com.austin.trading.service;

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
import java.util.List;
import java.util.Optional;

/**
 * AI 研究業務邏輯層（Claude Code Agent 檔案模式）。
 * <p>
 * 流程：Java 排程寫出研究請求 JSON → Claude Code Agent 讀取分析
 * → 寫回 Markdown → Java 透過 {@link #importFromFile} / {@link #importFromPath} 落表。
 * </p>
 */
@Service
public class AiResearchService {

    private static final Logger log = LoggerFactory.getLogger(AiResearchService.class);

    private final AiResearchLogRepository repository;
    private final AiClaudeConfig          aiClaudeConfig;

    public AiResearchService(AiResearchLogRepository repository, AiClaudeConfig aiClaudeConfig) {
        this.repository     = repository;
        this.aiClaudeConfig = aiClaudeConfig;
    }

    // ── 查詢方法 ──────────────────────────────────────────────────────────────

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

    // ── 檔案模式（Claude Code Agent 寫入結果後由 Java 落表）──────────────────

    /**
     * 從 {@code trading.ai.claude.research-output-path} 讀取最新研究結果（不落表）。
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
     * 將 Claude Code Agent 寫入的研究結果落表（預設路徑）。
     */
    public Optional<AiResearchResponse> importFromFile(
            LocalDate tradingDate, String researchType, String symbol) {
        return importFromPath(aiClaudeConfig.getResearchOutputPath(), tradingDate, researchType, symbol);
    }

    /**
     * 從指定路徑的 Markdown 檔案匯入研究結果到 DB。
     */
    public Optional<AiResearchResponse> importFromPath(
            String filePath, LocalDate tradingDate, String researchType, String symbol) {

        if (filePath == null || filePath.isBlank()) return Optional.empty();
        try {
            Path p = Paths.get(filePath);
            if (!Files.exists(p)) {
                log.debug("[AiResearchService] file not found: {}", filePath);
                return Optional.empty();
            }
            String content = Files.readString(p);
            AiResearchLogEntity entity = new AiResearchLogEntity();
            entity.setTradingDate(tradingDate);
            entity.setResearchType(researchType);
            entity.setSymbol(symbol);
            entity.setPromptSummary("(Claude Code Agent / file import)");
            entity.setResearchResult(content);
            entity.setModel("claude-code-agent");
            entity.setTokensUsed(0);
            AiResearchLogEntity saved = repository.save(entity);
            log.info("[AiResearchService] Imported id={} type={} date={}", saved.getId(), researchType, tradingDate);
            return Optional.of(toResponse(saved));
        } catch (IOException e) {
            log.warn("[AiResearchService] Failed to import from {}: {}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    // ── 私有 ─────────────────────────────────────────────────────────────────

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
}
