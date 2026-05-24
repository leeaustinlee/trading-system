package com.austin.trading.repository;

import com.austin.trading.entity.HotGroupRadarSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface HotGroupRadarSnapshotRepository extends JpaRepository<HotGroupRadarSnapshotEntity, Long> {
    List<HotGroupRadarSnapshotEntity> findByTradingDateAndSourcePhaseOrderByHotScoreDesc(LocalDate tradingDate, String sourcePhase);
    List<HotGroupRadarSnapshotEntity> findByTradingDateAndThemeTagOrderByHotScoreDesc(LocalDate tradingDate, String themeTag);

    @Transactional
    void deleteByTradingDateAndSourcePhase(LocalDate tradingDate, String sourcePhase);
}
