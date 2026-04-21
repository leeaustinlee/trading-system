package com.austin.trading.repository;

import com.austin.trading.entity.MarketRegimeDecisionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MarketRegimeDecisionRepository
        extends JpaRepository<MarketRegimeDecisionEntity, Long> {

    Optional<MarketRegimeDecisionEntity>
        findTopByTradingDateOrderByEvaluatedAtDescIdDesc(LocalDate tradingDate);

    Optional<MarketRegimeDecisionEntity>
        findTopByOrderByEvaluatedAtDescIdDesc();

    List<MarketRegimeDecisionEntity>
        findByTradingDateOrderByEvaluatedAtDescIdDesc(LocalDate tradingDate);

    @Query("SELECT r FROM MarketRegimeDecisionEntity r " +
           "WHERE (:from IS NULL OR r.tradingDate >= :from) " +
           "AND (:to   IS NULL OR r.tradingDate <= :to) " +
           "ORDER BY r.evaluatedAt DESC, r.id DESC")
    List<MarketRegimeDecisionEntity> findRecent(
            @Param("from") LocalDate from,
            @Param("to")   LocalDate to,
            Pageable pageable);
}
