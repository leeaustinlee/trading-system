package com.austin.trading.repository;

import com.austin.trading.entity.StockEvaluationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockEvaluationRepository extends JpaRepository<StockEvaluationEntity, Long> {

    List<StockEvaluationEntity> findByTradingDate(LocalDate tradingDate);

    Optional<StockEvaluationEntity> findByTradingDateAndSymbol(LocalDate tradingDate, String symbol);

    /** 按日期刪除（admin cleanup 用） */
    long deleteByTradingDate(LocalDate tradingDate);
}
