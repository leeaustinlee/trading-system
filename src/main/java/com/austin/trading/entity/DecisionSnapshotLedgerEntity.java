package com.austin.trading.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "decision_snapshot_ledger")
public class DecisionSnapshotLedgerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "final_decision_id", nullable = false)
    private Long finalDecisionId;

    @Column(name = "trading_date", nullable = false)
    private LocalDate tradingDate;

    @Column(name = "source_task_type", length = 30)
    private String sourceTaskType;

    @Column(name = "prefer_task_type", length = 30)
    private String preferTaskType;

    @Column(name = "ai_task_id")
    private Long aiTaskId;

    @Column(name = "ai_status", length = 30)
    private String aiStatus;

    @Column(name = "ai_readiness_mode", length = 30)
    private String aiReadinessMode;

    @Column(name = "fallback_reason", length = 100)
    private String fallbackReason;

    @Column(name = "final_decision_code", length = 30)
    private String finalDecisionCode;

    @Column(name = "selected_symbols_json", columnDefinition = "json")
    private String selectedSymbolsJson;

    @Column(name = "rejected_symbols_json", columnDefinition = "json")
    private String rejectedSymbolsJson;

    @Column(name = "watch_symbols_json", columnDefinition = "json")
    private String watchSymbolsJson;

    @Column(name = "merged_symbols_json", columnDefinition = "json")
    private String mergedSymbolsJson;

    @Column(name = "candidate_universe_json", columnDefinition = "json")
    private String candidateUniverseJson;

    @Column(name = "candidate_scores_json", columnDefinition = "json")
    private String candidateScoresJson;

    @Column(name = "market_context_json", columnDefinition = "json")
    private String marketContextJson;

    @Column(name = "gate_trace_json", columnDefinition = "json")
    private String gateTraceJson;

    @Column(name = "decision_trace_json", columnDefinition = "json")
    private String decisionTraceJson;

    @Column(name = "response_payload_json", columnDefinition = "json")
    private String responsePayloadJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFinalDecisionId() { return finalDecisionId; }
    public void setFinalDecisionId(Long finalDecisionId) { this.finalDecisionId = finalDecisionId; }
    public LocalDate getTradingDate() { return tradingDate; }
    public void setTradingDate(LocalDate tradingDate) { this.tradingDate = tradingDate; }
    public String getSourceTaskType() { return sourceTaskType; }
    public void setSourceTaskType(String sourceTaskType) { this.sourceTaskType = sourceTaskType; }
    public String getPreferTaskType() { return preferTaskType; }
    public void setPreferTaskType(String preferTaskType) { this.preferTaskType = preferTaskType; }
    public Long getAiTaskId() { return aiTaskId; }
    public void setAiTaskId(Long aiTaskId) { this.aiTaskId = aiTaskId; }
    public String getAiStatus() { return aiStatus; }
    public void setAiStatus(String aiStatus) { this.aiStatus = aiStatus; }
    public String getAiReadinessMode() { return aiReadinessMode; }
    public void setAiReadinessMode(String aiReadinessMode) { this.aiReadinessMode = aiReadinessMode; }
    public String getFallbackReason() { return fallbackReason; }
    public void setFallbackReason(String fallbackReason) { this.fallbackReason = fallbackReason; }
    public String getFinalDecisionCode() { return finalDecisionCode; }
    public void setFinalDecisionCode(String finalDecisionCode) { this.finalDecisionCode = finalDecisionCode; }
    public String getSelectedSymbolsJson() { return selectedSymbolsJson; }
    public void setSelectedSymbolsJson(String selectedSymbolsJson) { this.selectedSymbolsJson = selectedSymbolsJson; }
    public String getRejectedSymbolsJson() { return rejectedSymbolsJson; }
    public void setRejectedSymbolsJson(String rejectedSymbolsJson) { this.rejectedSymbolsJson = rejectedSymbolsJson; }
    public String getWatchSymbolsJson() { return watchSymbolsJson; }
    public void setWatchSymbolsJson(String watchSymbolsJson) { this.watchSymbolsJson = watchSymbolsJson; }
    public String getMergedSymbolsJson() { return mergedSymbolsJson; }
    public void setMergedSymbolsJson(String mergedSymbolsJson) { this.mergedSymbolsJson = mergedSymbolsJson; }
    public String getCandidateUniverseJson() { return candidateUniverseJson; }
    public void setCandidateUniverseJson(String candidateUniverseJson) { this.candidateUniverseJson = candidateUniverseJson; }
    public String getCandidateScoresJson() { return candidateScoresJson; }
    public void setCandidateScoresJson(String candidateScoresJson) { this.candidateScoresJson = candidateScoresJson; }
    public String getMarketContextJson() { return marketContextJson; }
    public void setMarketContextJson(String marketContextJson) { this.marketContextJson = marketContextJson; }
    public String getGateTraceJson() { return gateTraceJson; }
    public void setGateTraceJson(String gateTraceJson) { this.gateTraceJson = gateTraceJson; }
    public String getDecisionTraceJson() { return decisionTraceJson; }
    public void setDecisionTraceJson(String decisionTraceJson) { this.decisionTraceJson = decisionTraceJson; }
    public String getResponsePayloadJson() { return responsePayloadJson; }
    public void setResponsePayloadJson(String responsePayloadJson) { this.responsePayloadJson = responsePayloadJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
