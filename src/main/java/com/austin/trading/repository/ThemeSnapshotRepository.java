package com.austin.trading.repository;

import com.austin.trading.entity.ThemeSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ThemeSnapshotRepository extends JpaRepository<ThemeSnapshotEntity, Long> {

    List<ThemeSnapshotEntity> findByTradingDateOrderByFinalThemeScoreDesc(LocalDate tradingDate);

    List<ThemeSnapshotEntity> findByTradingDateOrderByRankingOrderAsc(LocalDate tradingDate);

    Optional<ThemeSnapshotEntity> findByTradingDateAndThemeTag(LocalDate tradingDate, String themeTag);
}
