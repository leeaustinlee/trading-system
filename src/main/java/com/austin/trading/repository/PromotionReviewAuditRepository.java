package com.austin.trading.repository;

import com.austin.trading.entity.PromotionReviewAuditEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface PromotionReviewAuditRepository extends JpaRepository<PromotionReviewAuditEntity, Long> {
    long countByTradingDate(LocalDate tradingDate);

    @Query("""
            select count(a) from PromotionReviewAuditEntity a
            where a.tradingDate = :date
              and not (a.action = 'CREATE' and a.actor = 'system/build')
            """)
    long countManualAuditsByDate(@Param("date") LocalDate date);

    @Modifying
    @Transactional
    @Query("""
            delete from PromotionReviewAuditEntity a
            where a.tradingDate = :date
              and a.action = 'CREATE'
              and a.actor = 'system/build'
            """)
    int deleteSystemBuildAuditsByDate(@Param("date") LocalDate date);

    void deleteByTradingDate(LocalDate tradingDate);
    List<PromotionReviewAuditEntity> findByTradingDateOrderByCreatedAtAscIdAsc(LocalDate tradingDate);
    List<PromotionReviewAuditEntity> findByTradingDateAndSymbolOrderByCreatedAtAscIdAsc(LocalDate tradingDate, String symbol);
}
