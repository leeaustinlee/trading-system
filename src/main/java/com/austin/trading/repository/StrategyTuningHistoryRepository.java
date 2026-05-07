package com.austin.trading.repository;

import com.austin.trading.entity.StrategyTuningHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StrategyTuningHistoryRepository extends JpaRepository<StrategyTuningHistoryEntity, Long> {
    List<StrategyTuningHistoryEntity> findByRecommendationIdOrderByCreatedAtDesc(Long recommendationId);
}
