package com.austin.trading.service;

import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.repository.CandidateStockRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MainstreamOverlapReportServiceTest {

    @Test
    void lowOverlapReturnsReasonHints() {
        CandidateStockRepository repo = mock(CandidateStockRepository.class);
        when(repo.findByTradingDateBetweenOrderByTradingDateDescScoreDesc(any(), any()))
                .thenReturn(List.of(candidate("其他強勢股"), candidate("其他強勢股"), candidate("半導體")));

        var result = new MainstreamOverlapReportService(repo).recent(30);
        assertThat((BigDecimal) result.get("candidateOverlapPct")).isLessThan(new BigDecimal("40"));
        assertThat(result.get("reasonHints").toString()).contains("theme");
    }

    private CandidateStockEntity candidate(String theme) {
        CandidateStockEntity c = new CandidateStockEntity();
        c.setThemeTag(theme);
        c.setReason("突破");
        return c;
    }
}
