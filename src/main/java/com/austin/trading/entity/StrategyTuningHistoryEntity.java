package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "strategy_tuning_history",
        indexes = @Index(name = "idx_sth_recommendation", columnList = "recommendation_id"))
public class StrategyTuningHistoryEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long recommendationId;
    private LocalDateTime appliedDate;
    private String targetModule;
    private String targetParameter;
    private String oldValue;
    private String newValue;
    private String appliedBy;
    private String rollbackValue;
    @Column(columnDefinition = "json") private String beforeMetricsJson;
    @Column(columnDefinition = "json") private String afterMetricsJson;
    private LocalDateTime createdAt;

    @PrePersist void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public Long getRecommendationId() { return recommendationId; }
    public void setRecommendationId(Long recommendationId) { this.recommendationId = recommendationId; }
    public LocalDateTime getAppliedDate() { return appliedDate; }
    public void setAppliedDate(LocalDateTime appliedDate) { this.appliedDate = appliedDate; }
    public String getTargetModule() { return targetModule; }
    public void setTargetModule(String targetModule) { this.targetModule = targetModule; }
    public String getTargetParameter() { return targetParameter; }
    public void setTargetParameter(String targetParameter) { this.targetParameter = targetParameter; }
    public String getOldValue() { return oldValue; }
    public void setOldValue(String oldValue) { this.oldValue = oldValue; }
    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }
    public String getAppliedBy() { return appliedBy; }
    public void setAppliedBy(String appliedBy) { this.appliedBy = appliedBy; }
    public String getRollbackValue() { return rollbackValue; }
    public void setRollbackValue(String rollbackValue) { this.rollbackValue = rollbackValue; }
    public String getBeforeMetricsJson() { return beforeMetricsJson; }
    public void setBeforeMetricsJson(String beforeMetricsJson) { this.beforeMetricsJson = beforeMetricsJson; }
    public String getAfterMetricsJson() { return afterMetricsJson; }
    public void setAfterMetricsJson(String afterMetricsJson) { this.afterMetricsJson = afterMetricsJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
