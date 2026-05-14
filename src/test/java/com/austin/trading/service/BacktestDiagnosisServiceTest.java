package com.austin.trading.service;

import com.austin.trading.engine.BacktestMetricsEngine;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BacktestDiagnosisServiceTest {

    @Test
    void recentDiagnosisReturnsTradeAndDataGapLayers() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository candidateRepo = mock(CandidateForwardTrackingRepository.class);
        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any()))
                .thenReturn(List.of(closed("SETUP", "TP1_HIT", "MEMORY", "BULL", "3.5")));
        when(candidateRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of());

        BacktestService service = new BacktestService(
                mock(BacktestRunRepository.class), mock(BacktestTradeRepository.class),
                mock(PositionRepository.class), new BacktestMetricsEngine(),
                mock(ScoreConfigService.class), new ObjectMapper(), paperRepo, candidateRepo);

        var result = service.recentDiagnosis(30);
        assertThat(result).containsKeys("tradeLayer", "strategyLayer", "aiLayer");
        assertThat(result.get("aiLayer").toString()).contains("DATA_GAP");
    }

    private PaperTradeEntity closed(String strategy, String exit, String theme, String regime, String pnl) {
        PaperTradeEntity e = new PaperTradeEntity();
        e.setEntryDate(LocalDate.now().minusDays(3));
        e.setStatus("CLOSED");
        e.setStrategyType(strategy);
        e.setExitReason(exit);
        e.setThemeTag(theme);
        e.setEntryRegime(regime);
        e.setPnlPct(new BigDecimal(pnl));
        e.setHoldingDays(2);
        return e;
    }
}
