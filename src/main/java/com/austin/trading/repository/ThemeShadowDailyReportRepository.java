package com.austin.trading.repository;

import com.austin.trading.entity.ThemeShadowDailyReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

/**
 * v2 Theme Engine Shadow Mode 每日報表 repository（PR1）。
 */
public interface ThemeShadowDailyReportRepository
        extends JpaRepository<ThemeShadowDailyReportEntity, Long> {

    Optional<ThemeShadowDailyReportEntity> findByTradingDate(LocalDate tradingDate);
}
