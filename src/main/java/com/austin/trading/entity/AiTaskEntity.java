package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 任務佇列（PR-2 新增）。
 * <p>
 * 每日各時段建立一筆任務，記錄候選股 + 狀態機流轉。
 * 流程：PENDING → CLAUDE_RUNNING → CLAUDE_DONE → CODEX_RUNNING → CODEX_DONE → FINALIZED
 * </p>
 */
@Entity
@Table(
        name = "ai_task",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_task",
                columnNames = {"trading_date", "task_type", "target_symbol"}
        ),
        indexes = {
                @Index(name = "idx_status",        columnList = "status"),
                @Index(name = "idx_type_date",     columnList = "task_type,trading_date")
        }
)
public class AiTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    /**
     * PREMARKET / OPENING / MIDDAY / POSTMARKET / T86_TOMORROW / STOCK_EVAL / CODEX_REVIEW
     */
    @Column(name = "task_type", nullable = false, length = 30)
    private String taskType;

    /** STOCK_EVAL 才會有，其他 null */
    @Column(name = "target_symbol", length = 20)
    private String targetSymbol;

    /** [{symbol, stockName, themeTag, javaStructureScore}, ...] */
    @Column(name = "target_candidates_json", columnDefinition = "json")
    private String targetCandidatesJson;

    /**
     * PENDING / CLAUDE_RUNNING / CLAUDE_DONE / CODEX_RUNNING / CODEX_DONE /
     * FINALIZED / FAILED / EXPIRED
     */
    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "prompt_summary", length = 2000)
    private String promptSummary;

    @Column(name = "prompt_file_path", length = 500)
    private String promptFilePath;

    @Column(name = "claude_result_markdown", columnDefinition = "LONGTEXT")
    private String claudeResultMarkdown;

    /** {"2303": 8.5, "3231": 7.8} */
    @Column(name = "claude_scores_json", columnDefinition = "json")
    private String claudeScoresJson;

    @Column(name = "codex_result_markdown", columnDefinition = "LONGTEXT")
    private String codexResultMarkdown;

    @Column(name = "codex_scores_json", columnDefinition = "json")
    private String codexScoresJson;

    /** ["6213"] 被 Codex veto 的標的 */
    @Column(name = "codex_veto_symbols_json", columnDefinition = "json")
    private String codexVetoSymbolsJson;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "claude_started_at")
    private LocalDateTime claudeStartedAt;

    @Column(name = "claude_done_at")
    private LocalDateTime claudeDoneAt;

    @Column(name = "codex_started_at")
    private LocalDateTime codexStartedAt;

    @Column(name = "codex_done_at")
    private LocalDateTime codexDoneAt;

    @Column(name = "finalized_at")
    private LocalDateTime finalizedAt;

    /** JPA optimistic lock。防止多人同時轉移狀態導致髒寫 */
    @jakarta.persistence.Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @Column(name = "last_transition_at")
    private LocalDateTime lastTransitionAt;

    @Column(name = "last_transition_reason", length = 100)
    private String lastTransitionReason;

    /** Claude/Codex submit 結果 hash，重送相同內容視為 idempotent。可為 claude 或 codex hash 合併 */
    @Column(name = "result_hash", length = 128)
    private String resultHash;

    // ── Getters / Setters ────────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }

    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }

    public String getTargetSymbol() { return targetSymbol; }
    public void setTargetSymbol(String targetSymbol) { this.targetSymbol = targetSymbol; }

    public String getTargetCandidatesJson() { return targetCandidatesJson; }
    public void setTargetCandidatesJson(String targetCandidatesJson) {
        this.targetCandidatesJson = targetCandidatesJson;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getPromptSummary() { return promptSummary; }
    public void setPromptSummary(String promptSummary) { this.promptSummary = promptSummary; }

    public String getPromptFilePath() { return promptFilePath; }
    public void setPromptFilePath(String promptFilePath) { this.promptFilePath = promptFilePath; }

    public String getClaudeResultMarkdown() { return claudeResultMarkdown; }
    public void setClaudeResultMarkdown(String claudeResultMarkdown) {
        this.claudeResultMarkdown = claudeResultMarkdown;
    }

    public String getClaudeScoresJson() { return claudeScoresJson; }
    public void setClaudeScoresJson(String claudeScoresJson) { this.claudeScoresJson = claudeScoresJson; }

    public String getCodexResultMarkdown() { return codexResultMarkdown; }
    public void setCodexResultMarkdown(String codexResultMarkdown) {
        this.codexResultMarkdown = codexResultMarkdown;
    }

    public String getCodexScoresJson() { return codexScoresJson; }
    public void setCodexScoresJson(String codexScoresJson) { this.codexScoresJson = codexScoresJson; }

    public String getCodexVetoSymbolsJson() { return codexVetoSymbolsJson; }
    public void setCodexVetoSymbolsJson(String codexVetoSymbolsJson) {
        this.codexVetoSymbolsJson = codexVetoSymbolsJson;
    }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public LocalDateTime getClaudeStartedAt() { return claudeStartedAt; }
    public void setClaudeStartedAt(LocalDateTime claudeStartedAt) { this.claudeStartedAt = claudeStartedAt; }

    public LocalDateTime getClaudeDoneAt() { return claudeDoneAt; }
    public void setClaudeDoneAt(LocalDateTime claudeDoneAt) { this.claudeDoneAt = claudeDoneAt; }

    public LocalDateTime getCodexStartedAt() { return codexStartedAt; }
    public void setCodexStartedAt(LocalDateTime codexStartedAt) { this.codexStartedAt = codexStartedAt; }

    public LocalDateTime getCodexDoneAt() { return codexDoneAt; }
    public void setCodexDoneAt(LocalDateTime codexDoneAt) { this.codexDoneAt = codexDoneAt; }

    public LocalDateTime getFinalizedAt() { return finalizedAt; }
    public void setFinalizedAt(LocalDateTime finalizedAt) { this.finalizedAt = finalizedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public LocalDateTime getLastTransitionAt() { return lastTransitionAt; }
    public void setLastTransitionAt(LocalDateTime lastTransitionAt) { this.lastTransitionAt = lastTransitionAt; }

    public String getLastTransitionReason() { return lastTransitionReason; }
    public void setLastTransitionReason(String lastTransitionReason) { this.lastTransitionReason = lastTransitionReason; }

    public String getResultHash() { return resultHash; }
    public void setResultHash(String resultHash) { this.resultHash = resultHash; }
}
