package com.austin.trading.repository;

import com.austin.trading.entity.ThemeReplayEdgeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface ThemeReplayEdgeRepository extends JpaRepository<ThemeReplayEdgeEntity, Long> {
    List<ThemeReplayEdgeEntity> findByTradingDateAndThemeTagOrderByIdAsc(LocalDate tradingDate, String themeTag);
    @Transactional
    void deleteByTradingDate(LocalDate tradingDate);
}
