package com.austin.trading.repository;

import com.austin.trading.entity.HotGroupStockSignalEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface HotGroupStockSignalRepository extends JpaRepository<HotGroupStockSignalEntity, Long> {
    List<HotGroupStockSignalEntity> findByTradingDateAndSourcePhaseOrderByRadarRankScoreDesc(LocalDate tradingDate, String sourcePhase);
    List<HotGroupStockSignalEntity> findByTradingDateAndThemeTagOrderByRadarRankScoreDesc(LocalDate tradingDate, String themeTag);
    List<HotGroupStockSignalEntity> findByTradingDateAndSymbolOrderByRadarRankScoreDesc(LocalDate tradingDate, String symbol);

    @Transactional
    long countByTradingDateAndSourcePhase(LocalDate tradingDate, String sourcePhase);
    void deleteByTradingDateAndSourcePhase(LocalDate tradingDate, String sourcePhase);
}
