package com.austin.trading.repository;

import com.austin.trading.domain.enums.TuningConfidence;
import com.austin.trading.domain.enums.TuningRecommendationStatus;
import com.austin.trading.entity.StrategyTuningRecommendationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StrategyTuningRecommendationRepository extends JpaRepository<StrategyTuningRecommendationEntity, Long> {
    List<StrategyTuningRecommendationEntity> findAllByOrderByCreatedAtDesc();
    List<StrategyTuningRecommendationEntity> findByStatusOrderByCreatedAtDesc(TuningRecommendationStatus status);
    long countByStatus(TuningRecommendationStatus status);
    Optional<StrategyTuningRecommendationEntity> findFirstByConfidenceOrderByCreatedAtDesc(TuningConfidence confidence);
    long countByRecommendationTypeAndStatus(com.austin.trading.domain.enums.TuningRecommendationType type,
                                            TuningRecommendationStatus status);
}
