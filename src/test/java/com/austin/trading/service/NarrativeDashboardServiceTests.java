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

class NarrativeDashboardServiceTests {

    @Test
    void dashboardRowsExposeThemeLifecycleAttentionAndCrowding() {
        LocalDate date = LocalDate.of(2026, 5, 21);
        KolThemeSignalDailySnapshotRepository repo = mock(KolThemeSignalDailySnapshotRepository.class);

        KolThemeSignalDailySnapshotEntity passive = snapshot(date, "被動元件", "POSITIVE",
                new BigDecimal("0.7800"), "LOW", 3, 5);
        KolThemeSignalDailySnapshotEntity aiPower = snapshot(date, "AI power", "POSITIVE",
                new BigDecimal("0.8200"), "MEDIUM", 5, 8);
        KolThemeSignalDailySnapshotEntity robotics = snapshot(date, "機器人", "POSITIVE",
                new BigDecimal("0.9100"), "HIGH", 7, 12);
        when(repo.findByTradingDateOrderByNetShadowBoostDesc(date)).thenReturn(List.of(robotics, aiPower, passive));

        NarrativeDashboardService service = new NarrativeDashboardService(repo);

        var dashboard = service.dashboard(date);

        assertThat(dashboard.tradingDate()).isEqualTo(date);
        assertThat(dashboard.weakSignalOnly()).isTrue();
        assertThat(dashboard.rows()).extracting("theme")
                .containsExactly("機器人", "AI power", "被動元件");
        assertThat(dashboard.rows().get(0).lifecycle()).isEqualTo("CROWDED");
        assertThat(dashboard.rows().get(0).attention()).isEqualByComparingTo("9.1");
        assertThat(dashboard.rows().get(0).crowding()).isEqualByComparingTo("8.7");
        assertThat(dashboard.rows().get(1).lifecycle()).isEqualTo("EXPANDING");
        assertThat(dashboard.rows().get(1).attention()).isEqualByComparingTo("8.2");
        assertThat(dashboard.rows().get(1).crowding()).isEqualByComparingTo("5.2");
        assertThat(dashboard.rows().get(2).lifecycle()).isEqualTo("EMERGING");
        assertThat(dashboard.rows().get(2).attention()).isEqualByComparingTo("7.8");
        assertThat(dashboard.rows().get(2).crowding()).isEqualByComparingTo("3.1");
    }

    private KolThemeSignalDailySnapshotEntity snapshot(LocalDate date, String theme, String direction,
                                                       BigDecimal positiveScore, String crowdingRisk,
                                                       int sourceCount, int evidenceCount) {
        KolThemeSignalDailySnapshotEntity e = new KolThemeSignalDailySnapshotEntity();
        e.setTradingDate(date);
        e.setThemeTag(theme);
        e.setDirection(direction);
        e.setPositiveScore(positiveScore);
        e.setNegativeScore(BigDecimal.ZERO);
        e.setNetShadowBoost(BigDecimal.ZERO);
        e.setCrowdingRisk(crowdingRisk);
        e.setSourceCount(sourceCount);
        e.setEvidenceCount(evidenceCount);
        return e;
    }
}
