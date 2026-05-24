package com.austin.trading.repository;

import com.austin.trading.entity.ThemeReplaySnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ThemeReplaySnapshotRepository extends JpaRepository<ThemeReplaySnapshotEntity, Long> {
    List<ThemeReplaySnapshotEntity> findByTradingDateOrderByThemeTagAsc(LocalDate tradingDate);
    Optional<ThemeReplaySnapshotEntity> findByTradingDateAndThemeTag(LocalDate tradingDate, String themeTag);

    @Query("select distinct s.tradingDate from ThemeReplaySnapshotEntity s order by s.tradingDate desc")
    List<LocalDate> findDistinctTradingDatesDesc();

    @Transactional
    long countByTradingDate(LocalDate tradingDate);
    void deleteByTradingDate(LocalDate tradingDate);
}
