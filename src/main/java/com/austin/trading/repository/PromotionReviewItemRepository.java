package com.austin.trading.repository;

import com.austin.trading.entity.PromotionReviewItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface PromotionReviewItemRepository extends JpaRepository<PromotionReviewItemEntity, Long> {
    List<PromotionReviewItemEntity> findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(LocalDate tradingDate);
    List<PromotionReviewItemEntity> findByTradingDateAndSymbolOrderByThemeTagAscSourceAsc(LocalDate tradingDate, String symbol);
    long countByTradingDate(LocalDate tradingDate);
    @Transactional
    void deleteByTradingDate(LocalDate tradingDate);
}
