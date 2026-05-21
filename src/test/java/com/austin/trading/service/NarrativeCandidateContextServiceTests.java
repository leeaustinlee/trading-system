package com.austin.trading.service;

import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NarrativeCandidateContextServiceTests {

    @Test
    void mergeContextAddsWeakSignalNarrativeContextWithoutChangingExistingPayload() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 21);
        KolThemeSignalDailySnapshotRepository repo = mock(KolThemeSignalDailySnapshotRepository.class);
        KolThemeSignalDailySnapshotEntity snapshot = new KolThemeSignalDailySnapshotEntity();
        snapshot.setTradingDate(date);
        snapshot.setThemeTag("被動元件");
        snapshot.setDirection("POSITIVE");
        snapshot.setPositiveScore(new BigDecimal("0.7800"));
        snapshot.setCrowdingRisk("LOW");
        snapshot.setSourceCount(3);
        snapshot.setEvidenceCount(5);
        snapshot.setNetShadowBoost(new BigDecimal("0.1200"));
        when(repo.findByTradingDateAndThemeTag(date, "被動元件")).thenReturn(List.of(snapshot));

        ObjectMapper mapper = new ObjectMapper();
        NarrativeCandidateContextService service = new NarrativeCandidateContextService(repo, new NarrativeDashboardService(repo), mapper);

        String merged = service.mergeIntoPayload(date, "被動元件", "{\"tradabilityTag\":\"主進場\"}");
        var root = mapper.readTree(merged);

        assertThat(root.path("tradabilityTag").asText()).isEqualTo("主進場");
        assertThat(root.path("narrativeContext").path("weakSignalOnly").asBoolean()).isTrue();
        assertThat(root.path("narrativeContext").path("theme").asText()).isEqualTo("被動元件");
        assertThat(root.path("narrativeContext").path("lifecycle").asText()).isEqualTo("EMERGING");
        assertThat(root.path("narrativeContext").path("attention").decimalValue()).isEqualByComparingTo("7.8");
        assertThat(root.path("narrativeContext").path("crowding").decimalValue()).isEqualByComparingTo("3.1");
    }

    @Test
    void mergeContextLeavesPayloadUntouchedWhenThemeMissing() {
        KolThemeSignalDailySnapshotRepository repo = mock(KolThemeSignalDailySnapshotRepository.class);
        ObjectMapper mapper = new ObjectMapper();
        NarrativeCandidateContextService service = new NarrativeCandidateContextService(repo, new NarrativeDashboardService(repo), mapper);

        assertThat(service.mergeIntoPayload(LocalDate.of(2026, 5, 21), null, "{\"a\":1}"))
                .isEqualTo("{\"a\":1}");
    }
}
