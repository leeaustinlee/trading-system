package com.austin.trading.repository;

import com.austin.trading.entity.TuningAfterMetricsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TuningAfterMetricsRepository extends JpaRepository<TuningAfterMetricsEntity, Long> {
    List<TuningAfterMetricsEntity> findByRecommendationIdOrderByHorizonDaysDesc(Long recommendationId);
    Optional<TuningAfterMetricsEntity> findByRecommendationIdAndHorizonDays(Long recommendationId, int horizonDays);
}
