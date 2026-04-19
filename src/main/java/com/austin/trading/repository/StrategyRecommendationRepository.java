package com.austin.trading.repository;

import com.austin.trading.entity.StrategyRecommendationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StrategyRecommendationRepository extends JpaRepository<StrategyRecommendationEntity, Long> {
    List<StrategyRecommendationEntity> findAllByOrderByCreatedAtDesc();
    List<StrategyRecommendationEntity> findByStatus(String status);
    List<StrategyRecommendationEntity> findBySourceRunId(Long sourceRunId);
}
