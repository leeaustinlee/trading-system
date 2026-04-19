package com.austin.trading.repository;

import com.austin.trading.entity.TradeReviewEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradeReviewRepository extends JpaRepository<TradeReviewEntity, Long> {
    List<TradeReviewEntity> findAllByOrderByCreatedAtDesc();
    List<TradeReviewEntity> findByPositionIdOrderByReviewVersionDesc(Long positionId);
    Optional<TradeReviewEntity> findTopByPositionIdOrderByReviewVersionDesc(Long positionId);
    List<TradeReviewEntity> findByPrimaryTag(String primaryTag);
}
