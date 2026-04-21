package com.austin.trading.repository;

import com.austin.trading.entity.ThemeStrengthDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ThemeStrengthDecisionRepository
        extends JpaRepository<ThemeStrengthDecisionEntity, Long> {

    List<ThemeStrengthDecisionEntity> findByTradingDateOrderByStrengthScoreDesc(LocalDate tradingDate);

    Optional<ThemeStrengthDecisionEntity> findTopByTradingDateAndThemeTagOrderByIdDesc(
            LocalDate tradingDate, String themeTag);
}
