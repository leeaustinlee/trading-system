package com.austin.trading.repository;

import com.austin.trading.entity.ThemePeerShadowCandidateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ThemePeerShadowCandidateRepository extends JpaRepository<ThemePeerShadowCandidateEntity, Long> {

    Optional<ThemePeerShadowCandidateEntity> findByTradingDateAndSourcePhaseAndLeaderSymbolAndSymbol(
            LocalDate tradingDate,
            String sourcePhase,
            String leaderSymbol,
            String symbol
    );

    List<ThemePeerShadowCandidateEntity> findByTradingDateAndSourcePhaseOrderByShadowRankScoreDesc(
            LocalDate tradingDate,
            String sourcePhase
    );

    List<ThemePeerShadowCandidateEntity> findByLeaderSymbolOrderByTradingDateDescShadowRankScoreDesc(String leaderSymbol);

    List<ThemePeerShadowCandidateEntity> findByTradingDateOrderBySourcePhaseAscLeaderSymbolAscShadowRankScoreDesc(LocalDate tradingDate);
}
