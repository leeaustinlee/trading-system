package com.austin.trading.service;

import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KolSignalShadowModeServiceTests {

    private CandidateStockRepository candidateRepo;
    private KolThemeSignalDailySnapshotRepository snapshotRepo;
    private KolSignalShadowModeService service;

    @BeforeEach
    void setUp() {
        candidateRepo = mock(CandidateStockRepository.class);
        snapshotRepo = mock(KolThemeSignalDailySnapshotRepository.class);
        service = new KolSignalShadowModeService(candidateRepo, snapshotRepo);
    }

    @Test
    void report_recomputesOnDemandAndIsNotPersisted() {
        LocalDate date = LocalDate.of(2026, 5, 13);
        CandidateStockEntity first = candidate("2382", "廣達", "AI伺服器", "1.0000");
        CandidateStockEntity second = candidate("2330", "台積電", "半導體", "2.0000");

        when(snapshotRepo.findByTradingDateOrderByNetShadowBoostDesc(date)).thenReturn(List.of());
        when(candidateRepo.findByTradingDateOrderByScoreDesc(eq(date), any(Pageable.class)))
                .thenReturn(List.of(first))
                .thenReturn(List.of(second));

        var firstReport = service.report(date);
        var secondReport = service.report(date);

        assertThat(firstReport.items()).extracting("symbol").containsExactly("2382");
        assertThat(secondReport.items()).extracting("symbol").containsExactly("2330");
        assertThat(secondReport.note()).contains("computedOnDemand=true").contains("not persisted");
        verify(candidateRepo, times(2)).findByTradingDateOrderByScoreDesc(eq(date), any(Pageable.class));
        verify(snapshotRepo, times(2)).findByTradingDateOrderByNetShadowBoostDesc(date);
    }

    private CandidateStockEntity candidate(String symbol, String stockName, String themeTag, String score) {
        CandidateStockEntity candidate = new CandidateStockEntity();
        candidate.setSymbol(symbol);
        candidate.setStockName(stockName);
        candidate.setThemeTag(themeTag);
        candidate.setScore(new BigDecimal(score));
        return candidate;
    }
}
