package com.austin.trading.repository;

import com.austin.trading.entity.PaperTradeExitLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface PaperTradeExitLogRepository extends JpaRepository<PaperTradeExitLogEntity, Long> {

    List<PaperTradeExitLogEntity> findByPaperTradeIdOrderByEvaluatedAtDesc(Long paperTradeId);

    List<PaperTradeExitLogEntity> findByEvaluatedAtBetweenOrderByEvaluatedAtDesc(LocalDateTime from, LocalDateTime to);
}
