package com.austin.trading.repository;

import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface KolThemeSignalDailySnapshotRepository extends JpaRepository<KolThemeSignalDailySnapshotEntity, Long> {
    List<KolThemeSignalDailySnapshotEntity> findByTradingDate(LocalDate tradingDate);
    List<KolThemeSignalDailySnapshotEntity> findByTradingDateOrderByNetShadowBoostDesc(LocalDate tradingDate);
    List<KolThemeSignalDailySnapshotEntity> findByTradingDateAndThemeTag(LocalDate tradingDate, String themeTag);
    Optional<KolThemeSignalDailySnapshotEntity> findByTradingDateAndThemeTagAndDirection(LocalDate tradingDate, String themeTag, String direction);
    long countByTradingDate(LocalDate tradingDate);
    long countByTradingDateBetween(LocalDate startDate, LocalDate endDate);

    @Query("select max(k.tradingDate) from KolThemeSignalDailySnapshotEntity k")
    LocalDate findLatestTradingDate();

    long deleteByTradingDate(LocalDate tradingDate);
}
