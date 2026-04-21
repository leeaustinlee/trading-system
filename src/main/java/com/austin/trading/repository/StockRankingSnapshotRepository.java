package com.austin.trading.repository;

import com.austin.trading.entity.StockRankingSnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StockRankingSnapshotRepository
        extends JpaRepository<StockRankingSnapshotEntity, Long> {

    /** All snapshots for a trading date, highest score first. */
    @Query("SELECT s FROM StockRankingSnapshotEntity s " +
           "WHERE s.tradingDate = :date " +
           "ORDER BY s.selectionScore DESC, s.id ASC")
    List<StockRankingSnapshotEntity> findByTradingDate(@Param("date") LocalDate date);

    /** Eligible-for-setup snapshots only, highest score first. */
    @Query("SELECT s FROM StockRankingSnapshotEntity s " +
           "WHERE s.tradingDate = :date AND s.eligibleForSetup = true " +
           "ORDER BY s.selectionScore DESC, s.id ASC")
    List<StockRankingSnapshotEntity> findEligibleByTradingDate(@Param("date") LocalDate date);

    Optional<StockRankingSnapshotEntity> findTopByTradingDateAndSymbolOrderByIdDesc(
            LocalDate tradingDate, String symbol);

    /** Recent ranked candidates across dates, newest first. */
    @Query("SELECT s FROM StockRankingSnapshotEntity s " +
           "WHERE s.tradingDate >= :since " +
           "ORDER BY s.tradingDate DESC, s.selectionScore DESC")
    List<StockRankingSnapshotEntity> findRecent(@Param("since") LocalDate since);
}
