package com.austin.trading.entity;

import com.austin.trading.domain.enums.TuningEvaluationStatus;
import com.austin.trading.domain.enums.TuningFinalDecision;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "tuning_evaluation_result",
        indexes = {
                @Index(name = "idx_ter_recommendation", columnList = "recommendation_id"),
                @Index(name = "idx_ter_status", columnList = "evaluation_status")
        })
public class TuningEvaluationResultEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long recommendationId;
    @Enumerated(EnumType.STRING) private TuningEvaluationStatus evaluationStatus;
    @Column(length = 1500) private String evaluationReason;
    private BigDecimal improvementScore;
    private BigDecimal riskScore;
    @Enumerated(EnumType.STRING) private TuningFinalDecision finalDecision;
    private LocalDateTime createdAt;

    @PrePersist void prePersist() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRecommendationId() { return recommendationId; }
    public void setRecommendationId(Long recommendationId) { this.recommendationId = recommendationId; }
    public TuningEvaluationStatus getEvaluationStatus() { return evaluationStatus; }
    public void setEvaluationStatus(TuningEvaluationStatus evaluationStatus) { this.evaluationStatus = evaluationStatus; }
    public String getEvaluationReason() { return evaluationReason; }
    public void setEvaluationReason(String evaluationReason) { this.evaluationReason = evaluationReason; }
    public BigDecimal getImprovementScore() { return improvementScore; }
    public void setImprovementScore(BigDecimal improvementScore) { this.improvementScore = improvementScore; }
    public BigDecimal getRiskScore() { return riskScore; }
    public void setRiskScore(BigDecimal riskScore) { this.riskScore = riskScore; }
    public TuningFinalDecision getFinalDecision() { return finalDecision; }
    public void setFinalDecision(TuningFinalDecision finalDecision) { this.finalDecision = finalDecision; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
