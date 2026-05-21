package com.austin.trading.service;

import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
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

    @Test
    void report_exposesNarrativeContextWhenCandidateThemeMatchesSnapshot() {
        LocalDate date = LocalDate.of(2026, 5, 21);
        CandidateStockEntity candidate = candidate("4989", "榮科", "PCB/載板/材料", "9.2100");
        KolThemeSignalDailySnapshotEntity snapshot = snapshot(date, "PCB/載板/材料", "POSITIVE",
                "0.7600", "0.0000", "0.1600", "MEDIUM", 1, 2);
        when(snapshotRepo.findByTradingDateOrderByNetShadowBoostDesc(date)).thenReturn(List.of(snapshot));
        when(candidateRepo.findByTradingDateOrderByScoreDesc(eq(date), any(Pageable.class))).thenReturn(List.of(candidate));

        var report = service.report(date);
        var item = report.items().get(0);

        assertThat(item.kolBoostShadow()).isEqualByComparingTo("0.1600");
        assertThat(item.narrativeContext()).isNotNull();
        assertThat(item.narrativeContext().weakSignalOnly()).isTrue();
        assertThat(item.narrativeContext().theme()).isEqualTo("PCB/載板/材料");
        assertThat(item.narrativeContext().direction()).isEqualTo("POSITIVE");
        assertThat(item.narrativeContext().attention()).isEqualByComparingTo("7.6");
        assertThat(item.narrativeContext().freshness()).isEqualTo("DAILY_SNAPSHOT");
        assertThat(item.narrativeContext().crowding()).isEqualByComparingTo("5.2");
        assertThat(item.note()).contains("production candidate score and final decision are unchanged");
    }

    private CandidateStockEntity candidate(String symbol, String stockName, String themeTag, String score) {
        CandidateStockEntity candidate = new CandidateStockEntity();
        candidate.setSymbol(symbol);
        candidate.setStockName(stockName);
        candidate.setThemeTag(themeTag);
        candidate.setScore(new BigDecimal(score));
        return candidate;
    }

    private KolThemeSignalDailySnapshotEntity snapshot(LocalDate date, String theme, String direction,
                                                       String positiveScore, String negativeScore, String boost,
                                                       String crowdingRisk, int sourceCount, int evidenceCount) {
        KolThemeSignalDailySnapshotEntity snapshot = new KolThemeSignalDailySnapshotEntity();
        snapshot.setTradingDate(date);
        snapshot.setThemeTag(theme);
        snapshot.setDirection(direction);
        snapshot.setPositiveScore(new BigDecimal(positiveScore));
        snapshot.setNegativeScore(new BigDecimal(negativeScore));
        snapshot.setNetShadowBoost(new BigDecimal(boost));
        snapshot.setCrowdingRisk(crowdingRisk);
        snapshot.setSourceCount(sourceCount);
        snapshot.setEvidenceCount(evidenceCount);
        return snapshot;
    }
}
