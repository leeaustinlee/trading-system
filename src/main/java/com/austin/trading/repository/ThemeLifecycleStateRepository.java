package com.austin.trading.repository;

import com.austin.trading.entity.ThemeLifecycleStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ThemeLifecycleStateRepository extends JpaRepository<ThemeLifecycleStateEntity, Long> {
    List<ThemeLifecycleStateEntity> findByTradingDateOrderByThemeTagAsc(LocalDate tradingDate);
    Optional<ThemeLifecycleStateEntity> findByTradingDateAndThemeTag(LocalDate tradingDate, String themeTag);
    Optional<ThemeLifecycleStateEntity> findFirstByThemeTagAndTradingDateLessThanOrderByTradingDateDesc(String themeTag, LocalDate tradingDate);
    @Transactional
    long countByTradingDate(LocalDate tradingDate);
    void deleteByTradingDate(LocalDate tradingDate);
}
