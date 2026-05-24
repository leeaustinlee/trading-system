package com.austin.trading.repository;

import com.austin.trading.entity.ThemeReplayNodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface ThemeReplayNodeRepository extends JpaRepository<ThemeReplayNodeEntity, Long> {
    List<ThemeReplayNodeEntity> findByTradingDateAndThemeTagOrderByIdAsc(LocalDate tradingDate, String themeTag);
    List<ThemeReplayNodeEntity> findByTradingDateOrderByThemeTagAscSymbolAsc(LocalDate tradingDate);
    @Transactional
    long countByTradingDate(LocalDate tradingDate);
    void deleteByTradingDate(LocalDate tradingDate);
}
