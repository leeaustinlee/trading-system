package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "strategy_recommendation",
        indexes = {
                @Index(name = "idx_sr_status", columnList = "status"),
                @Index(name = "idx_sr_key", columnList = "target_key")
        })
public class StrategyRecommendationEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recommendation_type", nullable = false, length = 50)
    private String recommendationType;

    @Column(name = "target_key", nullable = false, length = 100)
    private String targetKey;

    @Column(name = "current_value", length = 50)
    private String currentValue;

    @Column(name = "suggested_value", length = 50)
    private String suggestedValue;

    @Column(name = "confidence_level", nullable = false, length = 10)
    private String confidenceLevel;

    @Column(name = "reason", length = 1000)
    private String reason;

    @Column(name = "supporting_metrics_json", columnDefinition = "json")
    private String supportingMetricsJson;

    @Column(name = "source_run_id")
    private Long sourceRunId;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "NEW";

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // ── getters / setters ──
    public Long getId() { return id; }
    public String getRecommendationType() { return recommendationType; }
    public void setRecommendationType(String v) { this.recommendationType = v; }
    public String getTargetKey() { return targetKey; }
    public void setTargetKey(String v) { this.targetKey = v; }
    public String getCurrentValue() { return currentValue; }
    public void setCurrentValue(String v) { this.currentValue = v; }
    public String getSuggestedValue() { return suggestedValue; }
    public void setSuggestedValue(String v) { this.suggestedValue = v; }
    public String getConfidenceLevel() { return confidenceLevel; }
    public void setConfidenceLevel(String v) { this.confidenceLevel = v; }
    public String getReason() { return reason; }
    public void setReason(String v) { this.reason = v; }
    public String getSupportingMetricsJson() { return supportingMetricsJson; }
    public void setSupportingMetricsJson(String v) { this.supportingMetricsJson = v; }
    public Long getSourceRunId() { return sourceRunId; }
    public void setSourceRunId(Long v) { this.sourceRunId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
