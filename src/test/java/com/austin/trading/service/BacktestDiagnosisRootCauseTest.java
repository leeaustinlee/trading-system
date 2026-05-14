package com.austin.trading.service;

import com.austin.trading.engine.BacktestMetricsEngine;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BacktestDiagnosisRootCauseTest {

    @Test
    void recentDiagnosisRanksRootCausesWithEvidence() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository candidateRepo = mock(CandidateForwardTrackingRepository.class);
        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any()))
                .thenReturn(List.of(problemTrade()));
        when(candidateRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of(failedCandidate()));

        BacktestService service = new BacktestService(
                mock(BacktestRunRepository.class), mock(BacktestTradeRepository.class),
                mock(PositionRepository.class), new BacktestMetricsEngine(),
                mock(ScoreConfigService.class), new ObjectMapper(), paperRepo, candidateRepo);

        var result = service.recentDiagnosis(30);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ranking = (List<Map<String, Object>>) result.get("rootCauseRanking");

        assertThat(ranking).extracting(i -> i.get("name"))
                .contains("invalidPricePlanPct", "lowRrPct", "earlyExitPct", "stopTooTightPct",
                        "themeMisalignmentPct", "regimeMismatchPct", "aiScoreFailurePct");
        assertThat(ranking.toString()).contains("DATA_GAP").contains("2330");
    }

    private PaperTradeEntity problemTrade() {
        PaperTradeEntity e = new PaperTradeEntity();
        e.setEntryDate(LocalDate.now().minusDays(8));
        e.setSymbol("2330");
        e.setStatus("CLOSED");
        e.setSanityResult("INVALID");
        e.setEntryRrRatio(null);
        e.setExitReason("TRAILING_STOP");
        e.setMfePct(new BigDecimal("3.0"));
        e.setReturn5d(new BigDecimal("4.0"));
        e.setPnlPct(new BigDecimal("-1.0"));
        e.setEntryPrice(new BigDecimal("100"));
        e.setStopLossPrice(new BigDecimal("99"));
        e.setThemeTag("其他強勢股");
        e.setEntryRegime("WEAK");
        e.setHoldingDays(2);
        return e;
    }

    private CandidateForwardTrackingEntity failedCandidate() {
        CandidateForwardTrackingEntity e = new CandidateForwardTrackingEntity();
        e.setTradingDate(LocalDate.now().minusDays(8));
        e.setStockId("2330");
        e.setFinalScore(new BigDecimal("8.0"));
        e.setT5CloseReturnPct(BigDecimal.ZERO);
        return e;
    }
}
