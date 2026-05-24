package com.austin.trading.repository;

import com.austin.trading.entity.ThemeReplayMetricsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ThemeReplayMetricsRepository extends JpaRepository<ThemeReplayMetricsEntity, Long> {
    List<ThemeReplayMetricsEntity> findByTradingDateOrderByThemeTagAsc(LocalDate tradingDate);
    Optional<ThemeReplayMetricsEntity> findByTradingDateAndThemeTag(LocalDate tradingDate, String themeTag);

    @Query("select distinct m.tradingDate from ThemeReplayMetricsEntity m order by m.tradingDate desc")
    List<LocalDate> findDistinctTradingDatesDesc();

    @Transactional
    void deleteByTradingDate(LocalDate tradingDate);
}
