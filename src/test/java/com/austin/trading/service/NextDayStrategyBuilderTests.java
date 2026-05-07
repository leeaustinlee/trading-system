package com.austin.trading.service;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.domain.enums.MarketBias;
import com.austin.trading.engine.PositionIntelligenceEngine;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.PositionDailyReviewEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PositionDailyReviewRepository;
import com.austin.trading.repository.PositionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NextDayStrategyBuilderTests {
    @Test
    void buildsNextDayStrategyFromPositionsAndCandidates() {
        PositionRepository positionRepo = mock(PositionRepository.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        PositionDailyReviewRepository reviewRepo = mock(PositionDailyReviewRepository.class);
        TwseMisClient twseMisClient = mock(TwseMisClient.class);
        when(reviewRepo.save(any(PositionDailyReviewEntity.class))).thenAnswer(i -> i.getArgument(0));
        when(positionRepo.findByStatus("OPEN")).thenReturn(List.of(position("6770", "57.7", "54", "56")));
        CandidateStockEntity top = candidate("8039", "台虹", "78");
        when(candidateRepo.findTopByOrderByTradingDateDesc()).thenReturn(Optional.of(top));
        when(candidateRepo.findByTradingDateOrderByScoreDesc(any(LocalDate.class), any(Pageable.class))).thenReturn(List.of(top));

        NextDayStrategyBuilder builder = new NextDayStrategyBuilder(positionRepo, candidateRepo, reviewRepo, twseMisClient,
                new PositionIntelligenceEngine(), new PortfolioSwitchAnalyzer());

        var result = builder.buildStrategy();

        assertThat(result.positionsSummary()).hasSize(1);
        assertThat(result.switchPlan()).hasSize(1);
        assertThat(result.marketBias()).isIn(MarketBias.OFFENSIVE, MarketBias.WATCH, MarketBias.DEFENSIVE);
        assertThat(result.humanOnlyWarning()).contains("不會自動下單");
        verify(reviewRepo, atLeastOnce()).save(any(PositionDailyReviewEntity.class));
    }

    private PositionEntity position(String symbol, String avgCost, String close, String stop) {
        PositionEntity p = new PositionEntity();
        p.setSymbol(symbol);
        p.setStockName(symbol + "Name");
        p.setStatus("OPEN");
        p.setQty(new BigDecimal("1000"));
        p.setAvgCost(new BigDecimal(avgCost));
        p.setClosePrice(new BigDecimal(close));
        p.setStopLossPrice(new BigDecimal(stop));
        return p;
    }

    private CandidateStockEntity candidate(String symbol, String name, String score) {
        CandidateStockEntity c = new CandidateStockEntity();
        c.setTradingDate(LocalDate.of(2026, 5, 7));
        c.setSymbol(symbol);
        c.setStockName(name);
        c.setScore(new BigDecimal(score));
        c.setReason("breakout 續強");
        return c;
    }
}
