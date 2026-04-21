package com.austin.trading.repository;

import com.austin.trading.entity.SetupDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SetupDecisionRepository extends JpaRepository<SetupDecisionEntity, Long> {

    /** All setups for a date, valid ones first. */
    @Query("SELECT s FROM SetupDecisionEntity s " +
           "WHERE s.tradingDate = :date " +
           "ORDER BY s.valid DESC, s.id ASC")
    List<SetupDecisionEntity> findByTradingDate(@Param("date") LocalDate date);

    /** Valid setups only for a given date. */
    @Query("SELECT s FROM SetupDecisionEntity s " +
           "WHERE s.tradingDate = :date AND s.valid = true " +
           "ORDER BY s.id ASC")
    List<SetupDecisionEntity> findValidByTradingDate(@Param("date") LocalDate date);

    Optional<SetupDecisionEntity> findTopByTradingDateAndSymbolOrderByIdDesc(
            LocalDate tradingDate, String symbol);

    Optional<SetupDecisionEntity> findTopByTradingDateAndSymbolAndValidTrueOrderByIdDesc(
            LocalDate tradingDate, String symbol);
}
