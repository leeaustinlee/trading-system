package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "final_decision")
public class FinalDecisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    @Column(name = "summary", length = 1000)
    private String summary;

    @Column(name = "payload_json", columnDefinition = "json")
    private String payloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** v2.1 AI 追溯欄位：這筆 FinalDecision 使用了哪個 AI task 的結果 */
    @Column(name = "ai_task_id")
    private Long aiTaskId;

    /** FULL_AI_READY / PARTIAL_AI_READY / AI_NOT_READY */
    @Column(name = "ai_status", length = 30)
    private String aiStatus;

    /** AI_NOT_READY / AI_TIMEOUT / CODEX_MISSING / FILE_BRIDGE_PARSE_ERROR / ... */
    @Column(name = "fallback_reason", length = 60)
    private String fallbackReason;

    /** OPENING / PREMARKET fallback 等 */
    @Column(name = "source_task_type", length = 30)
    private String sourceTaskType;

    @Column(name = "claude_done_at")
    private LocalDateTime claudeDoneAt;

    @Column(name = "codex_done_at")
    private LocalDateTime codexDoneAt;

    public Long getId() { return id; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getDecision() { return decision; }
    public void setDecision(String decision) { this.decision = decision; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    public Long getAiTaskId() { return aiTaskId; }
    public void setAiTaskId(Long aiTaskId) { this.aiTaskId = aiTaskId; }

    public String getAiStatus() { return aiStatus; }
    public void setAiStatus(String aiStatus) { this.aiStatus = aiStatus; }

    public String getFallbackReason() { return fallbackReason; }
    public void setFallbackReason(String fallbackReason) { this.fallbackReason = fallbackReason; }

    public String getSourceTaskType() { return sourceTaskType; }
    public void setSourceTaskType(String sourceTaskType) { this.sourceTaskType = sourceTaskType; }

    public LocalDateTime getClaudeDoneAt() { return claudeDoneAt; }
    public void setClaudeDoneAt(LocalDateTime claudeDoneAt) { this.claudeDoneAt = claudeDoneAt; }

    public LocalDateTime getCodexDoneAt() { return codexDoneAt; }
    public void setCodexDoneAt(LocalDateTime codexDoneAt) { this.codexDoneAt = codexDoneAt; }
}
