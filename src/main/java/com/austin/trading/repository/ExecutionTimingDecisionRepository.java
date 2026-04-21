package com.austin.trading.repository;

import com.austin.trading.entity.ExecutionTimingDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExecutionTimingDecisionRepository
        extends JpaRepository<ExecutionTimingDecisionEntity, Long> {

    /** All timing decisions for a date, approved ones first. */
    @Query("SELECT t FROM ExecutionTimingDecisionEntity t " +
           "WHERE t.tradingDate = :date " +
           "ORDER BY t.approved DESC, t.id ASC")
    List<ExecutionTimingDecisionEntity> findByTradingDate(@Param("date") LocalDate date);

    /** Approved timing decisions for a date. */
    @Query("SELECT t FROM ExecutionTimingDecisionEntity t " +
           "WHERE t.tradingDate = :date AND t.approved = true " +
           "ORDER BY t.id ASC")
    List<ExecutionTimingDecisionEntity> findApprovedByTradingDate(@Param("date") LocalDate date);

    Optional<ExecutionTimingDecisionEntity> findTopByTradingDateAndSymbolOrderByIdDesc(
            LocalDate tradingDate, String symbol);

    Optional<ExecutionTimingDecisionEntity> findTopByTradingDateAndSymbolAndApprovedTrueOrderByIdDesc(
            LocalDate tradingDate, String symbol);
}
