package com.austin.trading.repository;

import com.austin.trading.entity.ExecutionDecisionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExecutionDecisionLogRepository
        extends JpaRepository<ExecutionDecisionLogEntity, Long> {

    /** All execution decisions for a date, ENTER rows first. */
    @Query("SELECT e FROM ExecutionDecisionLogEntity e " +
           "WHERE e.tradingDate = :date " +
           "ORDER BY CASE e.action WHEN 'ENTER' THEN 0 ELSE 1 END, e.id ASC")
    List<ExecutionDecisionLogEntity> findByTradingDate(@Param("date") LocalDate date);

    /** ENTER decisions only for a date. */
    @Query("SELECT e FROM ExecutionDecisionLogEntity e " +
           "WHERE e.tradingDate = :date AND e.action = 'ENTER' " +
           "ORDER BY e.id ASC")
    List<ExecutionDecisionLogEntity> findEnterByTradingDate(@Param("date") LocalDate date);

    Optional<ExecutionDecisionLogEntity> findTopByTradingDateAndSymbolOrderByIdDesc(
            LocalDate tradingDate, String symbol);
}
