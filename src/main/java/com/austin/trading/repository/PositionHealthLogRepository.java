package com.austin.trading.repository;

import com.austin.trading.entity.PositionHealthLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PositionHealthLogRepository extends JpaRepository<PositionHealthLogEntity, Long> {
    List<PositionHealthLogEntity> findByEvaluatedAtGreaterThanEqualOrderByEvaluatedAtDesc(LocalDateTime since);
    List<PositionHealthLogEntity> findBySymbolAndEvaluatedAtGreaterThanEqualOrderByEvaluatedAtDesc(String symbol, LocalDateTime since);
}
