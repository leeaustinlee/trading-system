package com.austin.trading.repository;

import com.austin.trading.entity.RankingTopNShadowResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Repository for shadow-only Top-N ranking results. */
public interface RankingTopNShadowResultRepository extends JpaRepository<RankingTopNShadowResultEntity, Long> {
    List<RankingTopNShadowResultEntity> findByTradingDateOrderByRankingRankAsc(LocalDate tradingDate);
    List<RankingTopNShadowResultEntity> findByTradingDateBetweenOrderByTradingDateDescRankingRankAsc(LocalDate startDate, LocalDate endDate);
    List<RankingTopNShadowResultEntity> findByTradingDateAndRunIdOrderByRankingRankAsc(LocalDate tradingDate, String runId);
    List<RankingTopNShadowResultEntity> findByTradingDateAndThemeTagOrderByRankingRankAsc(LocalDate tradingDate, String themeTag);
    Optional<RankingTopNShadowResultEntity> findByTradingDateAndRunIdAndSymbol(LocalDate tradingDate, String runId, String symbol);
}
