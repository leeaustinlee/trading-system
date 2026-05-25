package com.austin.trading.service;

import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NarrativeDashboardServiceTest {

    @Test
    void dashboardExposesLifecycleDistributionAndWarningCountersWithoutTradingPermission() {
        LocalDate date = LocalDate.of(2026, 5, 21);
        KolThemeSignalDailySnapshotRepository repo = mock(KolThemeSignalDailySnapshotRepository.class);
        NarrativeDashboardService service = new NarrativeDashboardService(repo);
        when(repo.findByTradingDateOrderByNetShadowBoostDesc(date)).thenReturn(List.of(
                snapshot(date, "AI power", "0.91", "0.01", "0.1800", "LOW", 6, 10),
                snapshot(date, "機器人", "0.88", "0.02", "0.1200", "HIGH", 5, 8),
                snapshot(date, "被動元件", "0.72", "0.01", "0.1000", "LOW", 2, 4)
        ));

        var response = service.dashboard(date);

        assertThat(response.weakSignalOnly()).isTrue();
        assertThat(response.lifecycleDistribution()).containsEntry("EXPANDING", 1L)
                .containsEntry("CROWDED", 1L)
                .containsEntry("EMERGING", 1L);
        assertThat(response.crowdedThemes()).containsExactly("機器人");
        assertThat(response.emergingThemes()).containsExactly("被動元件");
        assertThat(response.hottestThemes()).containsExactly("AI power", "機器人", "被動元件");
        assertThat(response.narrativeWarningCount()).isEqualTo(1);
    }

    private KolThemeSignalDailySnapshotEntity snapshot(LocalDate date, String theme, String positive, String negative,
                                                       String boost, String crowdingRisk, int sourceCount, int evidenceCount) {
        KolThemeSignalDailySnapshotEntity e = new KolThemeSignalDailySnapshotEntity();
        e.setTradingDate(date);
        e.setThemeTag(theme);
        e.setDirection("BULLISH");
        e.setPositiveScore(new BigDecimal(positive));
        e.setNegativeScore(new BigDecimal(negative));
        e.setNetShadowBoost(new BigDecimal(boost));
        e.setCrowdingRisk(crowdingRisk);
        e.setSourceCount(sourceCount);
        e.setEvidenceCount(evidenceCount);
        return e;
    }
}
