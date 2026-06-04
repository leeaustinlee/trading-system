package com.austin.trading.scheduler;

import com.austin.trading.domain.enums.StopWashoutOutcomeLabel;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.StopWashoutOutcomeEntity;
import com.austin.trading.entity.StructuralExitDecisionLogEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.StopWashoutOutcomeRepository;
import com.austin.trading.repository.StructuralExitDecisionLogRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StopWashoutOutcomeJobTest {

    @Test
    void marksWashoutReversalWhenFutureHighReclaimsRecentHigh() {
        StructuralExitDecisionLogRepository logRepo = mock(StructuralExitDecisionLogRepository.class);
        StopWashoutOutcomeRepository outcomeRepo = mock(StopWashoutOutcomeRepository.class);
        MarketIndexDailyRepository dailyRepo = mock(MarketIndexDailyRepository.class);
        StopWashoutOutcomeJob job = new StopWashoutOutcomeJob(logRepo, outcomeRepo, dailyRepo);

        StructuralExitDecisionLogEntity log = new StructuralExitDecisionLogEntity();
        log.setId(10L);
        log.setSymbol("2330");
        log.setEvaluationDate(LocalDate.of(2026, 6, 1));
        log.setEvaluatedAt(LocalDateTime.of(2026, 6, 1, 10, 0));
        log.setSourceDecisionStatus("EXIT");
        log.setArbiterTier("OBSERVE_1D");
        log.setCurrentPrice(new BigDecimal("100"));
        log.setRecentHigh(new BigDecimal("108"));

        when(logRepo.findSourceExitWithoutOutcome(any())).thenReturn(List.of(log));
        when(logRepo.findArbiterExitWithoutOutcome(any())).thenReturn(List.of());
        when(outcomeRepo.existsByStructuralExitLogIdAndOutcomeBasis(10L, "SOURCE_EXIT")).thenReturn(false);
        when(dailyRepo.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(
                eq("2330"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(
                        bar("2330", LocalDate.of(2026, 6, 2), "105"),
                        bar("2330", LocalDate.of(2026, 6, 3), "109"),
                        bar("2330", LocalDate.of(2026, 6, 4), "112")));
        when(outcomeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StopWashoutOutcomeJob.RefreshSummary summary = job.refreshOutcomes();

        assertEquals(1, summary.inserted());
        verify(outcomeRepo).save(argThat(row ->
                row instanceof StopWashoutOutcomeEntity
                        && StopWashoutOutcomeLabel.WASHOUT_REVERSAL.name().equals(((StopWashoutOutcomeEntity) row).getOutcomeLabel())
                        && "SOURCE_EXIT".equals(((StopWashoutOutcomeEntity) row).getOutcomeBasis())
                        && Boolean.TRUE.equals(((StopWashoutOutcomeEntity) row).getNewHigh3To10d())));
    }

    @Test
    void refreshesSeparateSourceAndArbiterOutcomeUniverses() {
        StructuralExitDecisionLogRepository logRepo = mock(StructuralExitDecisionLogRepository.class);
        StopWashoutOutcomeRepository outcomeRepo = mock(StopWashoutOutcomeRepository.class);
        MarketIndexDailyRepository dailyRepo = mock(MarketIndexDailyRepository.class);
        StopWashoutOutcomeJob job = new StopWashoutOutcomeJob(logRepo, outcomeRepo, dailyRepo);

        StructuralExitDecisionLogEntity sourceExit = log(10L, "EXIT", "OBSERVE_1D");
        StructuralExitDecisionLogEntity arbiterExit = log(20L, "WEAKEN", "EXIT_REVIEW");
        when(logRepo.findSourceExitWithoutOutcome(any())).thenReturn(List.of(sourceExit));
        when(logRepo.findArbiterExitWithoutOutcome(any())).thenReturn(List.of(arbiterExit));
        when(outcomeRepo.existsByStructuralExitLogIdAndOutcomeBasis(10L, "SOURCE_EXIT")).thenReturn(false);
        when(outcomeRepo.existsByStructuralExitLogIdAndOutcomeBasis(20L, "ARBITER_EXIT_SHADOW")).thenReturn(false);
        when(dailyRepo.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(eq("2330"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(bar("2330", LocalDate.of(2026, 6, 2), "105")));
        when(dailyRepo.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(eq("2317"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(bar("2317", LocalDate.of(2026, 6, 2), "105")));
        when(outcomeRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StopWashoutOutcomeJob.RefreshSummary summary = job.refreshOutcomes();

        assertEquals(2, summary.inserted());
        verify(outcomeRepo).save(argThat(row -> row instanceof StopWashoutOutcomeEntity
                && "SOURCE_EXIT".equals(((StopWashoutOutcomeEntity) row).getOutcomeBasis())
                && Long.valueOf(10L).equals(((StopWashoutOutcomeEntity) row).getStructuralExitLogId())));
        verify(outcomeRepo).save(argThat(row -> row instanceof StopWashoutOutcomeEntity
                && "ARBITER_EXIT_SHADOW".equals(((StopWashoutOutcomeEntity) row).getOutcomeBasis())
                && Long.valueOf(20L).equals(((StopWashoutOutcomeEntity) row).getStructuralExitLogId())));
    }

    private StructuralExitDecisionLogEntity log(Long id, String sourceStatus, String tier) {
        StructuralExitDecisionLogEntity log = new StructuralExitDecisionLogEntity();
        log.setId(id);
        log.setSymbol(id == 10L ? "2330" : "2317");
        log.setEvaluationDate(LocalDate.of(2026, 6, 1));
        log.setEvaluatedAt(LocalDateTime.of(2026, 6, 1, 10, 0));
        log.setSourceDecisionStatus(sourceStatus);
        log.setArbiterTier(tier);
        log.setCurrentPrice(new BigDecimal("100"));
        log.setRecentHigh(new BigDecimal("104"));
        return log;
    }

    private MarketIndexDailyEntity bar(String symbol, LocalDate date, String high) {
        return new MarketIndexDailyEntity(symbol, date,
                new BigDecimal("100"), new BigDecimal(high), new BigDecimal("99"), new BigDecimal("100"), 1000L);
    }
}
