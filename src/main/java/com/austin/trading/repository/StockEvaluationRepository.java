package com.austin.trading.repository;

import com.austin.trading.entity.StockEvaluationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface StockEvaluationRepository extends JpaRepository<StockEvaluationEntity, Long> {

    List<StockEvaluationEntity> findByTradingDate(LocalDate tradingDate);
}
