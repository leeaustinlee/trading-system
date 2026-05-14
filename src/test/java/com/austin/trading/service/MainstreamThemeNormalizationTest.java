package com.austin.trading.service;

import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.repository.CandidateStockRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MainstreamThemeNormalizationTest {

    @Test
    void normalizesThemeTagAndReasonIntoCanonicalBuckets() {
        CandidateStockRepository repo = mock(CandidateStockRepository.class);
        when(repo.findByTradingDateBetweenOrderByTradingDateDescScoreDesc(any(), any()))
                .thenReturn(List.of(
                        candidate("AI伺服器", "突破 GB200 供應鏈", false),
                        candidate(null, "水冷散熱續強", true),
                        candidate("其他強勢股", "無明確族群", false)
                ));

        var result = new MainstreamOverlapReportService(repo).recent(30);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) result.get("topThemeByCandidateCount");

        assertThat(top).extracting(i -> i.get("theme")).contains("AI_SERVER", "COOLING", "OTHER");
        assertThat((BigDecimal) result.get("candidateOverlapPct")).isEqualByComparingTo("66.67");
        assertThat((BigDecimal) result.get("unmappedPct")).isEqualByComparingTo("33.33");
        assertThat(result.get("institutionalFlowThemes").toString()).contains("DATA_GAP");
        assertThat(result.get("dataGaps").toString()).contains("DATA_GAP");
    }

    private CandidateStockEntity candidate(String theme, String reason, boolean momentum) {
        CandidateStockEntity c = new CandidateStockEntity();
        c.setThemeTag(theme);
        c.setReason(reason);
        c.setMomentumCandidate(momentum);
        return c;
    }
}
