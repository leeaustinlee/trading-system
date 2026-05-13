package com.austin.trading.scheduler;

import com.austin.trading.client.TaifexClient;
import com.austin.trading.client.TwseInstitutionalClient;
import com.austin.trading.client.TwseMisClient;
import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.MarketSnapshotRepository;
import com.austin.trading.service.AiTaskService;
import com.austin.trading.service.CandidateScanService;
import com.austin.trading.service.ClaudeCodeRequestWriterService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.SchedulerLogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CandidateUniverseResolverTests {

    @Test
    void premarketResolver_prefersTodayCandidateUniverseOverYesterday() {
        LocalDate today = LocalDate.of(2026, 5, 13);
        LocalDate yesterday = LocalDate.of(2026, 5, 12);
        CandidateScanService scanService = mock(CandidateScanService.class);
        CandidateResponse todayCandidate = candidate(today, "3645");
        when(scanService.getCandidatesByDate(today, 10)).thenReturn(List.of(todayCandidate));

        PremarketDataPrepJob job = new PremarketDataPrepJob(
                mock(TaifexClient.class), mock(TwseMisClient.class), scanService,
                mock(MarketSnapshotRepository.class), mock(SchedulerLogService.class),
                mock(ClaudeCodeRequestWriterService.class), mock(DailyOrchestrationService.class),
                mock(AiTaskService.class));

        PremarketDataPrepJob.CandidateSourceResolution resolution =
                job.resolveCandidateSource(today, yesterday);

        assertThat(resolution.sourceDate()).isEqualTo(today);
        assertThat(resolution.policy()).isEqualTo("TODAY");
        assertThat(resolution.candidates()).extracting(CandidateResponse::symbol)
                .containsExactly("3645");
    }

    @Test
    void t86Resolver_prefersLatestFutureCandidateUniverseOverToday() {
        LocalDate today = LocalDate.of(2026, 5, 12);
        LocalDate latestDate = LocalDate.of(2026, 5, 13);
        CandidateStockRepository repository = mock(CandidateStockRepository.class);
        CandidateStockEntity latest = new CandidateStockEntity();
        latest.setTradingDate(latestDate);
        latest.setSymbol("3645");
        when(repository.findTopByOrderByTradingDateDesc()).thenReturn(Optional.of(latest));

        T86DataPrepJob job = new T86DataPrepJob(
                mock(TwseInstitutionalClient.class), repository, mock(SchedulerLogService.class),
                mock(DailyOrchestrationService.class), mock(AiTaskService.class),
                mock(ClaudeCodeRequestWriterService.class));

        assertThat(job.resolveCandidateTradingDate(today)).isEqualTo(latestDate);
    }

    @Test
    void t86Resolver_fallsBackToTodayWhenNoLatestCandidateExists() {
        LocalDate today = LocalDate.of(2026, 5, 12);
        CandidateStockRepository repository = mock(CandidateStockRepository.class);
        when(repository.findTopByOrderByTradingDateDesc()).thenReturn(Optional.empty());
        when(repository.findByTradingDateOrderByScoreDesc(eq(today), eq(PageRequest.of(0, 1))))
                .thenReturn(List.of(new CandidateStockEntity()));

        T86DataPrepJob job = new T86DataPrepJob(
                mock(TwseInstitutionalClient.class), repository, mock(SchedulerLogService.class),
                mock(DailyOrchestrationService.class), mock(AiTaskService.class),
                mock(ClaudeCodeRequestWriterService.class));

        assertThat(job.resolveCandidateTradingDate(today)).isEqualTo(today);
        verify(repository).findTopByOrderByTradingDateDesc();
    }

    private CandidateResponse candidate(LocalDate date, String symbol) {
        return new CandidateResponse(date, symbol, "n" + symbol, null, null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
