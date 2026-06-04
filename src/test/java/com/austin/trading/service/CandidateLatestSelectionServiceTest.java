package com.austin.trading.service;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.dto.request.CandidateBatchItemRequest;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.engine.MomentumCandidateEngine;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.StockEvaluationRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandidateLatestSelectionServiceTest {

    private CandidateStockRepository candidateStockRepository;
    private StockEvaluationRepository stockEvaluationRepository;
    private CandidateScanService service;

    @BeforeEach
    void setUp() {
        candidateStockRepository = mock(CandidateStockRepository.class);
        stockEvaluationRepository = mock(StockEvaluationRepository.class);
        ThemeSnapshotRepository themeSnapshotRepository = mock(ThemeSnapshotRepository.class);
        TwseMisClient twseMisClient = mock(TwseMisClient.class);
        ScoreConfigService scoreConfigService = mock(ScoreConfigService.class);
        MomentumCandidateEngine momentumCandidateEngine = new MomentumCandidateEngine(scoreConfigService);

        when(stockEvaluationRepository.findByTradingDate(any())).thenReturn(List.of());

        service = new CandidateScanService(
                candidateStockRepository,
                stockEvaluationRepository,
                themeSnapshotRepository,
                twseMisClient,
                momentumCandidateEngine,
                scoreConfigService,
                new ObjectMapper()
        );
    }

    @Test
    void latestCandidatesUseNewestDbTradingDateEvenWhenTodayExists() {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        CandidateStockEntity todayCandidate = candidate(today, "1111", "今日股", "5.0");
        CandidateStockEntity tomorrowCandidate = candidate(tomorrow, "2222", "明日股", "8.0");

        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(eq(today), any(Pageable.class)))
                .thenReturn(List.of(todayCandidate));
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(eq(tomorrow), any(Pageable.class)))
                .thenReturn(List.of(tomorrowCandidate));
        when(candidateStockRepository.findTopByOrderByTradingDateDesc())
                .thenReturn(Optional.of(tomorrowCandidate));
        when(candidateStockRepository.findTopByTradingDateGreaterThanOrderByTradingDateAsc(today))
                .thenReturn(Optional.of(tomorrowCandidate));

        List<CandidateResponse> current = service.getCurrentCandidates(20);
        List<CandidateResponse> latest = service.getLatestCandidates(20);
        List<CandidateResponse> next = service.getNextCandidates(20);

        assertThat(current).extracting(CandidateResponse::symbol).containsExactly("1111");
        assertThat(latest).extracting(CandidateResponse::symbol).containsExactly("2222");
        assertThat(latest).extracting(CandidateResponse::tradingDate).containsExactly(tomorrow);
        assertThat(next).extracting(CandidateResponse::symbol).containsExactly("2222");
    }

    @Test
    void saveBatchWithExplicitNextTradingDateWritesNextRowsWithoutAffectingCurrent() {
        LocalDate today = LocalDate.now();
        LocalDate tomorrow = today.plusDays(1);
        CandidateStockEntity todayCandidate = candidate(today, "1111", "今日股", "5.0");
        CandidateStockEntity tomorrowCandidate = candidate(tomorrow, "2222", "明日股", "8.0");

        when(candidateStockRepository.findByTradingDateAndSymbol(eq(tomorrow), eq("2222")))
                .thenReturn(Optional.empty());
        when(candidateStockRepository.save(any(CandidateStockEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(eq(today), any(Pageable.class)))
                .thenReturn(List.of(todayCandidate));
        when(candidateStockRepository.findByTradingDateOrderByScoreDesc(eq(tomorrow), any(Pageable.class)))
                .thenReturn(List.of(tomorrowCandidate));
        when(candidateStockRepository.findTopByOrderByTradingDateDesc())
                .thenReturn(Optional.of(tomorrowCandidate));
        when(candidateStockRepository.findTopByTradingDateGreaterThanOrderByTradingDateAsc(today))
                .thenReturn(Optional.of(tomorrowCandidate));

        var result = service.saveBatchWithGate(List.of(new CandidateBatchItemRequest(
                tomorrow,
                "2222",
                "明日股",
                new BigDecimal("8.0"),
                "tomorrow plan",
                null,
                null,
                "{}",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        )));

        assertThat(result.accepted()).isEqualTo(1);
        assertThat(service.getCurrentCandidates(20)).extracting(CandidateResponse::symbol).containsExactly("1111");
        assertThat(service.getLatestCandidates(20)).extracting(CandidateResponse::symbol).containsExactly("2222");
        assertThat(service.getNextCandidates(20)).extracting(CandidateResponse::symbol).containsExactly("2222");
        verify(candidateStockRepository).findByTradingDateAndSymbol(tomorrow, "2222");
    }

    private CandidateStockEntity candidate(LocalDate date, String symbol, String name, String score) {
        CandidateStockEntity entity = new CandidateStockEntity();
        entity.setTradingDate(date);
        entity.setSymbol(symbol);
        entity.setStockName(name);
        entity.setScore(new BigDecimal(score));
        entity.setReason("test");
        return entity;
    }
}
