package com.austin.trading.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "tuning_after_metrics",
        indexes = {
                @Index(name = "idx_tam_recommendation", columnList = "recommendation_id"),
                @Index(name = "idx_tam_eval", columnList = "recommendation_id,evaluation_date,horizon_days")
        })
public class TuningAfterMetricsEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long recommendationId;
    private LocalDate evaluationDate;
    private int horizonDays;
    private int sampleSize;
    private BigDecimal winRate;
    private BigDecimal avgReturn;
    private BigDecimal avgMfe;
    private BigDecimal avgMae;
    private BigDecimal avgRelativeReturn;
    private BigDecimal benchmarkReturn;
    private LocalDateTime createdAt;

    @PrePersist void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRecommendationId() { return recommendationId; }
    public void setRecommendationId(Long recommendationId) { this.recommendationId = recommendationId; }
    public LocalDate getEvaluationDate() { return evaluationDate; }
    public void setEvaluationDate(LocalDate evaluationDate) { this.evaluationDate = evaluationDate; }
    public int getHorizonDays() { return horizonDays; }
    public void setHorizonDays(int horizonDays) { this.horizonDays = horizonDays; }
    public int getSampleSize() { return sampleSize; }
    public void setSampleSize(int sampleSize) { this.sampleSize = sampleSize; }
    public BigDecimal getWinRate() { return winRate; }
    public void setWinRate(BigDecimal winRate) { this.winRate = winRate; }
    public BigDecimal getAvgReturn() { return avgReturn; }
    public void setAvgReturn(BigDecimal avgReturn) { this.avgReturn = avgReturn; }
    public BigDecimal getAvgMfe() { return avgMfe; }
    public void setAvgMfe(BigDecimal avgMfe) { this.avgMfe = avgMfe; }
    public BigDecimal getAvgMae() { return avgMae; }
    public void setAvgMae(BigDecimal avgMae) { this.avgMae = avgMae; }
    public BigDecimal getAvgRelativeReturn() { return avgRelativeReturn; }
    public void setAvgRelativeReturn(BigDecimal avgRelativeReturn) { this.avgRelativeReturn = avgRelativeReturn; }
    public BigDecimal getBenchmarkReturn() { return benchmarkReturn; }
    public void setBenchmarkReturn(BigDecimal benchmarkReturn) { this.benchmarkReturn = benchmarkReturn; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
