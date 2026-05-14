package com.austin.trading.service;

import com.austin.trading.engine.BacktestMetricsEngine;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BacktestDiagnosisThemeLostTest {

    @Test
    void detectsThemeLostInTradeWhenCandidateLayerHasMainstreamTheme() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        LocalDate date = LocalDate.now().minusDays(3);
        PaperTradeEntity trade = new PaperTradeEntity();
        trade.setEntryDate(date);
        trade.setSymbol("2330");
        trade.setStatus("OPEN");
        trade.setThemeTag("UNKNOWN");
        CandidateForwardTrackingEntity forward = new CandidateForwardTrackingEntity();
        forward.setTradingDate(date);
        forward.setStockId("2330");
        forward.setThemeTag("半導體");
        forward.setThemeReason("ASIC 半導體");
        CandidateStockEntity candidate = new CandidateStockEntity();
        candidate.setTradingDate(date);
        candidate.setSymbol("2330");
        candidate.setThemeTag("半導體");
        candidate.setReason("ASIC 半導體");

        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any())).thenReturn(List.of(trade));
        when(forwardRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of(forward));
        when(candidateRepo.findByTradingDateBetweenOrderByTradingDateDescScoreDesc(any(), any())).thenReturn(List.of(candidate));

        BacktestService service = new BacktestService(
                mock(BacktestRunRepository.class), mock(BacktestTradeRepository.class),
                mock(PositionRepository.class), new BacktestMetricsEngine(),
                mock(ScoreConfigService.class), new ObjectMapper(), paperRepo, forwardRepo, candidateRepo);

        var result = service.recentDiagnosis(30);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) result.get("rootCauseRanking");

        Map<String, Object> themeLost = ranking.stream()
                .filter(i -> "themeLostInTradePct".equals(i.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(themeLost.get("count")).isEqualTo(1);
        assertThat((BigDecimal) themeLost.get("pct")).isEqualByComparingTo("100.00");

        Map<String, Object> misalignment = ranking.stream()
                .filter(i -> "themeMisalignmentPct".equals(i.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(misalignment.get("count")).isEqualTo(0);
    }
}
