package com.austin.trading.service;

import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.PaperTradeRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CandidateForwardTrackingServiceTest {

    @Test
    void summaryReportsPaperFallbackWhenCandidateTableEmpty() {
        CandidateForwardTrackingRepository repo = mock(CandidateForwardTrackingRepository.class);
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        when(repo.count()).thenReturn(0L);
        when(paperRepo.count()).thenReturn(12L);

        var summary = new CandidateForwardTrackingService(repo, paperRepo).summary();
        assertThat(summary.get("status").toString()).contains("DATA_GAP");
        assertThat(summary.get("source")).isEqualTo("PAPER_TRADE_FALLBACK");
        assertThat(summary.get("total")).isEqualTo(12L);
        assertThat(summary.get("paperTradeRows")).isEqualTo(12L);
    }

    @Test
    void backfillFromPaperTradesIsIdempotentByDateSymbolDecision() {
        CandidateForwardTrackingRepository repo = mock(CandidateForwardTrackingRepository.class);
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any())).thenReturn(List.of());
        when(repo.findByTradingDateAndStockIdAndFinalDecision(any(), any(), any())).thenReturn(Optional.empty());

        var result = new CandidateForwardTrackingService(repo, paperRepo).backfillFromPaperTrades(30);
        assertThat(result.get("written")).isEqualTo(0);
        verify(repo, never()).save(any());
    }
}
