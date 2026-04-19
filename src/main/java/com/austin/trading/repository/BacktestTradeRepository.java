package com.austin.trading.repository;

import com.austin.trading.entity.BacktestTradeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BacktestTradeRepository extends JpaRepository<BacktestTradeEntity, Long> {
    List<BacktestTradeEntity> findByBacktestRunIdOrderByEntryDateAsc(Long backtestRunId);
}
