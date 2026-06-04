package com.austin.trading.repository;

import com.austin.trading.entity.ThemeSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ThemeSnapshotRepository extends JpaRepository<ThemeSnapshotEntity, Long> {

    List<ThemeSnapshotEntity> findByTradingDateOrderByFinalThemeScoreDesc(LocalDate tradingDate);

    List<ThemeSnapshotEntity> findByTradingDateOrderByRankingOrderAsc(LocalDate tradingDate);

    Optional<ThemeSnapshotEntity> findByTradingDateAndThemeTag(LocalDate tradingDate, String themeTag);

    List<ThemeSnapshotEntity> findByTradingDateBetween(LocalDate startDate, LocalDate endDate);

    long countByTradingDate(LocalDate tradingDate);

    @Query("select max(t.tradingDate) from ThemeSnapshotEntity t where t.tradingDate <= :today")
    LocalDate findLatestValidTradingDate(@Param("today") LocalDate today);

    @Query("select max(t.tradingDate) from ThemeSnapshotEntity t")
    LocalDate findLatestTradingDate();

    @Query("select count(t) from ThemeSnapshotEntity t where t.tradingDate > :today")
    long countFutureRows(@Param("today") LocalDate today);

    @Query("select max(t.tradingDate) from ThemeSnapshotEntity t where lower(t.themeTag) = lower(:themeTag) and t.tradingDate <= :today")
    LocalDate findLatestValidTradingDateForTheme(@Param("themeTag") String themeTag, @Param("today") LocalDate today);

    @Query("select count(t) from ThemeSnapshotEntity t where lower(t.themeTag) = lower(:themeTag) and t.tradingDate > :today")
    long countFutureRowsForTheme(@Param("themeTag") String themeTag, @Param("today") LocalDate today);
}
