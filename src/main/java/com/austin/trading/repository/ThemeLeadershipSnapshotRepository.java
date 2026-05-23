package com.austin.trading.repository;

import com.austin.trading.entity.ThemeLeadershipSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface ThemeLeadershipSnapshotRepository extends JpaRepository<ThemeLeadershipSnapshotEntity, Long> {

    List<ThemeLeadershipSnapshotEntity> findByTradingDateOrderByLeaderRankAsc(LocalDate tradingDate);

    List<ThemeLeadershipSnapshotEntity> findByTradingDateAndSourcePhaseOrderByLeaderRankAsc(LocalDate tradingDate, String sourcePhase);

    @Transactional
    void deleteByTradingDateAndSourcePhase(LocalDate tradingDate, String sourcePhase);
}
