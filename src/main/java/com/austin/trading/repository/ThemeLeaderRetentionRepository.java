package com.austin.trading.repository;

import com.austin.trading.entity.ThemeLeaderRetentionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ThemeLeaderRetentionRepository extends JpaRepository<ThemeLeaderRetentionEntity, Long> {

    Optional<ThemeLeaderRetentionEntity> findByTradingDateAndTargetPhaseAndSymbol(
            LocalDate tradingDate,
            String targetPhase,
            String symbol
    );

    List<ThemeLeaderRetentionEntity> findByTargetPhaseAndActiveTrueAndTradingDateLessThanEqualOrderByTradingDateDescLeaderRankAsc(
            String targetPhase,
            LocalDate tradingDate
    );
}
