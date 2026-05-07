package com.austin.trading.repository;

import com.austin.trading.domain.enums.TuningEvaluationStatus;
import com.austin.trading.domain.enums.TuningFinalDecision;
import com.austin.trading.entity.TuningEvaluationResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TuningEvaluationResultRepository extends JpaRepository<TuningEvaluationResultEntity, Long> {
    Optional<TuningEvaluationResultEntity> findFirstByRecommendationIdOrderByCreatedAtDesc(Long recommendationId);
    List<TuningEvaluationResultEntity> findByRecommendationIdOrderByCreatedAtDesc(Long recommendationId);
    long countByEvaluationStatus(TuningEvaluationStatus status);
    long countByFinalDecision(TuningFinalDecision finalDecision);
    Optional<TuningEvaluationResultEntity> findFirstByEvaluationStatusOrderByCreatedAtDesc(TuningEvaluationStatus status);
}
