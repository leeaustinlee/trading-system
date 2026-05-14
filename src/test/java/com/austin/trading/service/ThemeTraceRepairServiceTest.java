package com.austin.trading.service;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PaperTradeRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ThemeTraceRepairServiceTest {

    @Test
    void repairsCandidateForwardTraceAndShadowPaperTradeFromSameDayCandidate() {
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        LocalDate date = LocalDate.now().minusDays(2);
        CandidateForwardTrackingEntity forward = new CandidateForwardTrackingEntity();
        forward.setTradingDate(date);
        forward.setStockId("2330");
        forward.setThemeTag("UNKNOWN");
        PaperTradeEntity paper = new PaperTradeEntity();
        paper.setEntryDate(date);
        paper.setSymbol("2330");
        paper.setTradeId("T1");
        paper.setEntryPrice(BigDecimal.TEN);
        paper.setShadow(true);
        paper.setThemeTag(null);
        CandidateStockEntity candidate = new CandidateStockEntity();
        candidate.setTradingDate(date);
        candidate.setSymbol("2330");
        candidate.setThemeTag("半導體");
        candidate.setReason("ASIC 半導體延續");

        when(forwardRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of(forward));
        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any())).thenReturn(List.of(paper));
        when(candidateRepo.findByTradingDateAndSymbol(date, "2330")).thenReturn(Optional.of(candidate));

        var result = new ThemeTraceRepairService(forwardRepo, candidateRepo, paperRepo).repair(60);

        assertThat(result.get("repairedRows")).isEqualTo(2);
        assertThat(forward.getThemeTag()).isEqualTo("半導體");
        assertThat(forward.getThemeReason()).isEqualTo("ASIC 半導體延續");
        assertThat(paper.getThemeTag()).isEqualTo("半導體");
        verify(forwardRepo).save(forward);
        verify(paperRepo).save(paper);
    }
}
