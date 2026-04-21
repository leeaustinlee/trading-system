package com.austin.trading.repository;

import com.austin.trading.entity.PortfolioRiskDecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PortfolioRiskDecisionRepository
        extends JpaRepository<PortfolioRiskDecisionEntity, Long> {

    /** All risk decisions for a date (gate rows + per-candidate rows), approved first. */
    @Query("SELECT r FROM PortfolioRiskDecisionEntity r " +
           "WHERE r.tradingDate = :date " +
           "ORDER BY r.approved DESC, r.id ASC")
    List<PortfolioRiskDecisionEntity> findByTradingDate(@Param("date") LocalDate date);

    /** Approved per-candidate decisions for a date (symbol IS NOT NULL). */
    @Query("SELECT r FROM PortfolioRiskDecisionEntity r " +
           "WHERE r.tradingDate = :date AND r.approved = true AND r.symbol IS NOT NULL " +
           "ORDER BY r.id ASC")
    List<PortfolioRiskDecisionEntity> findApprovedCandidatesByTradingDate(@Param("date") LocalDate date);

    Optional<PortfolioRiskDecisionEntity> findTopByTradingDateAndSymbolOrderByIdDesc(
            LocalDate tradingDate, String symbol);
}
