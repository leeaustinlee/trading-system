package com.austin.trading.repository;

import com.austin.trading.entity.PositionDailyReviewEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PositionDailyReviewRepository extends JpaRepository<PositionDailyReviewEntity, Long> {
    List<PositionDailyReviewEntity> findByTradingDateOrderByStockId(LocalDate tradingDate);
}
