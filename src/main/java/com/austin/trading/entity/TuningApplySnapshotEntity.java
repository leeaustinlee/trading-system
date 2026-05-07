package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "tuning_apply_snapshot",
        indexes = {
                @Index(name = "idx_tas_recommendation", columnList = "recommendation_id"),
                @Index(name = "idx_tas_applied_date", columnList = "applied_date")
        })
public class TuningApplySnapshotEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long recommendationId;
    private LocalDate appliedDate;
    private int lookbackDays;
    private BigDecimal decisionWinRate;
    private BigDecimal decisionAvgReturn;
    private BigDecimal decisionAvgMfe;
    private BigDecimal decisionAvgMae;
    @Column(columnDefinition = "json") private String strategyMetricsJson;
    @Column(columnDefinition = "json") private String gateMetricsJson;
    @Column(columnDefinition = "json") private String scoreBucketMetricsJson;
    private LocalDateTime createdAt;

    @PrePersist void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRecommendationId() { return recommendationId; }
    public void setRecommendationId(Long recommendationId) { this.recommendationId = recommendationId; }
    public LocalDate getAppliedDate() { return appliedDate; }
    public void setAppliedDate(LocalDate appliedDate) { this.appliedDate = appliedDate; }
    public int getLookbackDays() { return lookbackDays; }
    public void setLookbackDays(int lookbackDays) { this.lookbackDays = lookbackDays; }
    public BigDecimal getDecisionWinRate() { return decisionWinRate; }
    public void setDecisionWinRate(BigDecimal decisionWinRate) { this.decisionWinRate = decisionWinRate; }
    public BigDecimal getDecisionAvgReturn() { return decisionAvgReturn; }
    public void setDecisionAvgReturn(BigDecimal decisionAvgReturn) { this.decisionAvgReturn = decisionAvgReturn; }
    public BigDecimal getDecisionAvgMfe() { return decisionAvgMfe; }
    public void setDecisionAvgMfe(BigDecimal decisionAvgMfe) { this.decisionAvgMfe = decisionAvgMfe; }
    public BigDecimal getDecisionAvgMae() { return decisionAvgMae; }
    public void setDecisionAvgMae(BigDecimal decisionAvgMae) { this.decisionAvgMae = decisionAvgMae; }
    public String getStrategyMetricsJson() { return strategyMetricsJson; }
    public void setStrategyMetricsJson(String strategyMetricsJson) { this.strategyMetricsJson = strategyMetricsJson; }
    public String getGateMetricsJson() { return gateMetricsJson; }
    public void setGateMetricsJson(String gateMetricsJson) { this.gateMetricsJson = gateMetricsJson; }
    public String getScoreBucketMetricsJson() { return scoreBucketMetricsJson; }
    public void setScoreBucketMetricsJson(String scoreBucketMetricsJson) { this.scoreBucketMetricsJson = scoreBucketMetricsJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
