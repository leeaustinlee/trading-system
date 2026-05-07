package com.austin.trading.entity;

import com.austin.trading.domain.enums.TuningConfidence;
import com.austin.trading.domain.enums.TuningRecommendationStatus;
import com.austin.trading.domain.enums.TuningRecommendationType;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "strategy_tuning_recommendation",
        indexes = {
                @Index(name = "idx_str_status", columnList = "status"),
                @Index(name = "idx_str_generated_date", columnList = "generated_date"),
                @Index(name = "idx_str_target", columnList = "target_module,target_parameter")
        })
public class StrategyTuningRecommendationEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private LocalDate generatedDate;
    private int lookbackDays;
    @Enumerated(EnumType.STRING) private TuningRecommendationType recommendationType;
    private String targetModule;
    private String targetParameter;
    private String currentValue;
    private String suggestedValue;
    @Column(length = 1000) private String suggestedAction;
    @Column(length = 1500) private String reason;
    @Column(columnDefinition = "json") private String evidenceJson;
    private Integer sampleSize;
    private BigDecimal winRate;
    private BigDecimal avgReturnPct;
    private BigDecimal avgMfePct;
    private BigDecimal avgMaePct;
    private BigDecimal missedRallyRate;
    private BigDecimal benchmarkRelativeReturnPct;
    @Enumerated(EnumType.STRING) private TuningConfidence confidence;
    @Enumerated(EnumType.STRING) private TuningRecommendationStatus status = TuningRecommendationStatus.PENDING;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private String rejectedBy;
    private LocalDateTime rejectedAt;
    private LocalDateTime appliedAt;
    private String rollbackValue;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist void prePersist() { createdAt = LocalDateTime.now(); updatedAt = createdAt; }
    @PreUpdate void preUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public LocalDate getGeneratedDate() { return generatedDate; }
    public void setGeneratedDate(LocalDate generatedDate) { this.generatedDate = generatedDate; }
    public int getLookbackDays() { return lookbackDays; }
    public void setLookbackDays(int lookbackDays) { this.lookbackDays = lookbackDays; }
    public TuningRecommendationType getRecommendationType() { return recommendationType; }
    public void setRecommendationType(TuningRecommendationType recommendationType) { this.recommendationType = recommendationType; }
    public String getTargetModule() { return targetModule; }
    public void setTargetModule(String targetModule) { this.targetModule = targetModule; }
    public String getTargetParameter() { return targetParameter; }
    public void setTargetParameter(String targetParameter) { this.targetParameter = targetParameter; }
    public String getCurrentValue() { return currentValue; }
    public void setCurrentValue(String currentValue) { this.currentValue = currentValue; }
    public String getSuggestedValue() { return suggestedValue; }
    public void setSuggestedValue(String suggestedValue) { this.suggestedValue = suggestedValue; }
    public String getSuggestedAction() { return suggestedAction; }
    public void setSuggestedAction(String suggestedAction) { this.suggestedAction = suggestedAction; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public Integer getSampleSize() { return sampleSize; }
    public void setSampleSize(Integer sampleSize) { this.sampleSize = sampleSize; }
    public BigDecimal getWinRate() { return winRate; }
    public void setWinRate(BigDecimal winRate) { this.winRate = winRate; }
    public BigDecimal getAvgReturnPct() { return avgReturnPct; }
    public void setAvgReturnPct(BigDecimal avgReturnPct) { this.avgReturnPct = avgReturnPct; }
    public BigDecimal getAvgMfePct() { return avgMfePct; }
    public void setAvgMfePct(BigDecimal avgMfePct) { this.avgMfePct = avgMfePct; }
    public BigDecimal getAvgMaePct() { return avgMaePct; }
    public void setAvgMaePct(BigDecimal avgMaePct) { this.avgMaePct = avgMaePct; }
    public BigDecimal getMissedRallyRate() { return missedRallyRate; }
    public void setMissedRallyRate(BigDecimal missedRallyRate) { this.missedRallyRate = missedRallyRate; }
    public BigDecimal getBenchmarkRelativeReturnPct() { return benchmarkRelativeReturnPct; }
    public void setBenchmarkRelativeReturnPct(BigDecimal benchmarkRelativeReturnPct) { this.benchmarkRelativeReturnPct = benchmarkRelativeReturnPct; }
    public TuningConfidence getConfidence() { return confidence; }
    public void setConfidence(TuningConfidence confidence) { this.confidence = confidence; }
    public TuningRecommendationStatus getStatus() { return status; }
    public void setStatus(TuningRecommendationStatus status) { this.status = status; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }
    public String getRejectedBy() { return rejectedBy; }
    public void setRejectedBy(String rejectedBy) { this.rejectedBy = rejectedBy; }
    public LocalDateTime getRejectedAt() { return rejectedAt; }
    public void setRejectedAt(LocalDateTime rejectedAt) { this.rejectedAt = rejectedAt; }
    public LocalDateTime getAppliedAt() { return appliedAt; }
    public void setAppliedAt(LocalDateTime appliedAt) { this.appliedAt = appliedAt; }
    public String getRollbackValue() { return rollbackValue; }
    public void setRollbackValue(String rollbackValue) { this.rollbackValue = rollbackValue; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
