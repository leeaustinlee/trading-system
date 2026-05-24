package com.austin.trading.repository;

import com.austin.trading.entity.CandidateThemeRadarTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface CandidateThemeRadarTraceRepository extends JpaRepository<CandidateThemeRadarTraceEntity, Long> {
    List<CandidateThemeRadarTraceEntity> findByTradingDateAndSymbol(LocalDate tradingDate, String symbol);

    long countByTradingDate(LocalDate tradingDate);

    @Transactional
    void deleteByTradingDate(LocalDate tradingDate);
}
