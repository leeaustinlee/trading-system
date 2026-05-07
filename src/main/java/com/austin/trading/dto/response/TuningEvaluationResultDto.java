package com.austin.trading.dto.response;

import com.austin.trading.entity.TuningEvaluationResultEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TuningEvaluationResultDto(
        Long id,
        Long recommendationId,
        String evaluationStatus,
        String evaluationReason,
        BigDecimal improvementScore,
        BigDecimal riskScore,
        String finalDecision,
        LocalDateTime createdAt
) {
    public static TuningEvaluationResultDto from(TuningEvaluationResultEntity e) {
        return new TuningEvaluationResultDto(e.getId(), e.getRecommendationId(),
                e.getEvaluationStatus() == null ? null : e.getEvaluationStatus().name(),
                e.getEvaluationReason(), e.getImprovementScore(), e.getRiskScore(),
                e.getFinalDecision() == null ? null : e.getFinalDecision().name(), e.getCreatedAt());
    }
}
