package com.austin.trading.repository;

import com.austin.trading.entity.PromotionReviewAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface PromotionReviewAuditRepository extends JpaRepository<PromotionReviewAuditEntity, Long> {
    List<PromotionReviewAuditEntity> findByTradingDateOrderByCreatedAtAscIdAsc(LocalDate tradingDate);
    List<PromotionReviewAuditEntity> findByTradingDateAndSymbolOrderByCreatedAtAscIdAsc(LocalDate tradingDate, String symbol);
}
