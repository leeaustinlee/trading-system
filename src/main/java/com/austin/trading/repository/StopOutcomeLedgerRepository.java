package com.austin.trading.repository;

import com.austin.trading.entity.StopOutcomeLedgerEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface StopOutcomeLedgerRepository extends JpaRepository<StopOutcomeLedgerEntity, Long> {
    Optional<StopOutcomeLedgerEntity> findByPaperTradeId(Long paperTradeId);

    List<StopOutcomeLedgerEntity> findByExitDateGreaterThanEqualOrderByExitDateDescIdDesc(LocalDate from);

    List<StopOutcomeLedgerEntity> findBySymbolAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc(String symbol, LocalDate from);

    @Query("""
            SELECT s.outcomeLabel, COUNT(s)
              FROM StopOutcomeLedgerEntity s
             GROUP BY s.outcomeLabel
             ORDER BY COUNT(s) DESC
            """)
    List<Object[]> countByOutcomeLabel();

    @Query("""
            SELECT s.exitReason, COUNT(s)
              FROM StopOutcomeLedgerEntity s
             GROUP BY s.exitReason
             ORDER BY COUNT(s) DESC
            """)
    List<Object[]> countByExitReason();
}
