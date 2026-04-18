package com.austin.trading.repository;

import com.austin.trading.entity.PositionReviewLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PositionReviewLogRepository extends JpaRepository<PositionReviewLogEntity, Long> {

    List<PositionReviewLogEntity> findByReviewDateOrderByCreatedAtDesc(LocalDate reviewDate);

    Optional<PositionReviewLogEntity> findTopByPositionIdOrderByCreatedAtDesc(Long positionId);

    List<PositionReviewLogEntity> findByPositionIdOrderByCreatedAtDesc(Long positionId);
}
