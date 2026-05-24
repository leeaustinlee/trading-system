package com.austin.trading.repository;

import com.austin.trading.entity.ResearchUniverseItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface ResearchUniverseItemRepository extends JpaRepository<ResearchUniverseItemEntity, Long> {
    List<ResearchUniverseItemEntity> findByTradingDateOrderByThemeTagAscSymbolAscSourceAsc(LocalDate tradingDate);
    List<ResearchUniverseItemEntity> findByTradingDateAndThemeTagOrderBySymbolAscSourceAsc(LocalDate tradingDate, String themeTag);
    List<ResearchUniverseItemEntity> findByTradingDateAndSymbolOrderByThemeTagAscSourceAsc(LocalDate tradingDate, String symbol);
    List<ResearchUniverseItemEntity> findByTradingDateAndGovernanceStatusOrderByThemeTagAscSymbolAsc(LocalDate tradingDate, String governanceStatus);
    @Transactional
    void deleteByTradingDate(LocalDate tradingDate);
}
