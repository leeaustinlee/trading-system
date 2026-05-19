package com.austin.trading.repository;

import com.austin.trading.entity.ShadowExitComparisonEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ShadowExitComparisonRepository extends JpaRepository<ShadowExitComparisonEntity, Long> {
    Optional<ShadowExitComparisonEntity> findByTradeRefTypeAndTradeRefId(String tradeRefType, Long tradeRefId);
    List<ShadowExitComparisonEntity> findByEvaluatedAtGreaterThanEqualOrderByEvaluatedAtDesc(LocalDateTime since);
}
