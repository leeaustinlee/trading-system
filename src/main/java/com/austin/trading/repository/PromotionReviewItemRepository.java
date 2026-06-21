package com.austin.trading.repository;

import com.austin.trading.entity.PromotionReviewItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PromotionReviewItemRepository extends JpaRepository<PromotionReviewItemEntity, Long> {
    List<PromotionReviewItemEntity> findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(LocalDate tradingDate);
    List<PromotionReviewItemEntity> findByTradingDateAndSymbolOrderByThemeTagAscSourceAsc(LocalDate tradingDate, String symbol);
    List<PromotionReviewItemEntity> findByTradingDateBetweenAndCurrentStatusOrderByTradingDateAscThemeTagAscSymbolAscSourceAsc(
            LocalDate startDate, LocalDate endDate, String currentStatus);
    long countByTradingDate(LocalDate tradingDate);

    @Query("""
            select count(i) from PromotionReviewItemEntity i
            where i.tradingDate = :date
              and (i.reviewer is not null
                   or i.reviewedAt is not null
                   or i.decisionReason is not null
                   or i.currentStatus in ('WATCH_ONLY', 'CANDIDATE_POOL_SHADOW', 'NEED_MORE_EVIDENCE',
                                          'REJECTED', 'BLOCKED_BY_RISK', 'BLOCKED_BY_GOVERNANCE'))
            """)
    long countManualItemsByDate(@Param("date") LocalDate date);

    @Query("""
            select i from PromotionReviewItemEntity i
            where i.tradingDate = :date
              and (i.reviewer is not null
                   or i.reviewedAt is not null
                   or i.decisionReason is not null
                   or i.currentStatus in ('WATCH_ONLY', 'CANDIDATE_POOL_SHADOW', 'NEED_MORE_EVIDENCE',
                                          'REJECTED', 'BLOCKED_BY_RISK', 'BLOCKED_BY_GOVERNANCE'))
            order by i.themeTag asc, i.symbol asc, i.source asc
            """)
    List<PromotionReviewItemEntity> findManualItemsByDate(@Param("date") LocalDate date);

    @Modifying
    @Transactional
    @Query("""
            delete from PromotionReviewItemEntity i
            where i.tradingDate = :date
              and i.reviewer is null
              and i.reviewedAt is null
              and i.decisionReason is null
              and i.currentStatus in ('PENDING_REVIEW', 'RESEARCH_ONLY')
            """)
    int deleteSystemGeneratedByDate(@Param("date") LocalDate date);

    @Query("""
            select i from PromotionReviewItemEntity i
            where i.tradingDate = :date
              and i.symbol = :symbol
              and i.themeTag = :themeTag
              and i.source = :source
            """)
    Optional<PromotionReviewItemEntity> findByNaturalKey(@Param("date") LocalDate date,
                                                         @Param("symbol") String symbol,
                                                         @Param("themeTag") String themeTag,
                                                         @Param("source") String source);

    @Transactional
    void deleteByTradingDate(LocalDate tradingDate);
}
