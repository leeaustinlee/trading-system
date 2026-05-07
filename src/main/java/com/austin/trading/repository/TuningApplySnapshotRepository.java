package com.austin.trading.repository;

import com.austin.trading.entity.TuningApplySnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TuningApplySnapshotRepository extends JpaRepository<TuningApplySnapshotEntity, Long> {
    Optional<TuningApplySnapshotEntity> findByRecommendationId(Long recommendationId);
}
