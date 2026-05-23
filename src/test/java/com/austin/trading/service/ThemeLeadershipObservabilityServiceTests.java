package com.austin.trading.service;

import com.austin.trading.entity.ThemeLeadershipSnapshotEntity;
import com.austin.trading.repository.ThemeLeadershipSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThemeLeadershipObservabilityServiceTests {

    private final ThemeLeadershipSnapshotRepository repository = mock(ThemeLeadershipSnapshotRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ThemeLeadershipObservabilityService service = new ThemeLeadershipObservabilityService(repository, objectMapper);

    @Test
    void snapshotsYageoAsPassiveComponentLeaderAndDivergenceWithoutChangingTradingFlow() throws Exception {
        LocalDate date = LocalDate.of(2026, 5, 22);
        String payload = """
                {
                  "strong_themes": [
                    {"Theme":"PCB/載板/材料","Score":158.5},
                    {"Theme":"半導體/IC","Score":132.2}
                  ],
                  "hot_stocks": [
                    {"Code":"2327","Name":"國巨*","Theme":"其他強勢股","ChangePct":9.97,"AmountYi":455.0,"Score":24.43,"NearHigh":1,"IsBoardLotAffordable":false,"TradabilityTag":"題材指標，不列主進場"},
                    {"Code":"4958","Name":"臻鼎-KY","Theme":"PCB/載板/材料","ChangePct":9.90,"AmountYi":260.28,"Score":23.63,"NearHigh":1,"IsBoardLotAffordable":false,"TradabilityTag":"題材指標，不列主進場"}
                  ],
                  "super_strong_5": [
                    {"Code":"2327","Name":"國巨*","Theme":"其他強勢股","ChangePct":9.97,"AmountYi":455.0,"Score":24.43,"NearHigh":1,"IsBoardLotAffordable":false,"TradabilityTag":"題材指標，不列主進場"}
                  ]
                }
                """;

        var result = service.generateSnapshot(date, "POSTMARKET", objectMapper.readTree(payload));

        assertThat(result.totalLeaders()).isEqualTo(2);
        assertThat(result.divergenceCount()).isEqualTo(1);
        assertThat(result.items()).anySatisfy(item -> {
            assertThat(item.symbol()).isEqualTo("2327");
            assertThat(item.themeCategory()).isEqualTo(ThemeTaxonomyClassifier.ELECTRONICS_COMPONENTS);
            assertThat(item.subTheme()).isEqualTo(ThemeTaxonomyClassifier.MLCC);
            assertThat(item.taxonomyStatus()).isEqualTo("TAXONOMY_GAP");
            assertThat(item.divergenceFlags()).contains("HOT_STOCK_TOP10_MISSING_FROM_STRONG_THEMES", "OTHER_STRONG_LEADER_RECLASSIFIED");
            assertThat(item.tradable()).isFalse();
            assertThat(item.tradableReason()).contains("題材指標");
        });

        verify(repository).deleteByTradingDateAndSourcePhase(date, "POSTMARKET");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ThemeLeadershipSnapshotEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).anySatisfy(entity -> {
            assertThat(entity.getSymbol()).isEqualTo("2327");
            assertThat(entity.getThemeCategory()).isEqualTo(ThemeTaxonomyClassifier.ELECTRONICS_COMPONENTS);
            assertThat(entity.getSubTheme()).isEqualTo(ThemeTaxonomyClassifier.MLCC);
            assertThat(entity.getTaxonomyStatus()).isEqualTo("TAXONOMY_GAP");
            assertThat(entity.getDivergenceFlagsJson()).contains("HOT_STOCK_TOP10_MISSING_FROM_STRONG_THEMES");
        });
    }

    @Test
    void queryDivergenceReturnsOnlyRowsWithFlags() {
        LocalDate date = LocalDate.of(2026, 5, 22);
        ThemeLeadershipSnapshotEntity yageo = new ThemeLeadershipSnapshotEntity();
        yageo.setTradingDate(date);
        yageo.setSourcePhase("POSTMARKET");
        yageo.setSymbol("2327");
        yageo.setStockName("國巨*");
        yageo.setThemeTag("其他強勢股");
        yageo.setThemeCategory(ThemeTaxonomyClassifier.ELECTRONICS_COMPONENTS);
        yageo.setSubTheme(ThemeTaxonomyClassifier.MLCC);
        yageo.setDivergenceFlagsJson("[\"HOT_STOCK_TOP10_MISSING_FROM_STRONG_THEMES\"]");
        ThemeLeadershipSnapshotEntity normal = new ThemeLeadershipSnapshotEntity();
        normal.setTradingDate(date);
        normal.setSourcePhase("POSTMARKET");
        normal.setSymbol("4958");
        normal.setStockName("臻鼎-KY");
        normal.setThemeTag("PCB/載板/材料");
        normal.setDivergenceFlagsJson("[]");
        when(repository.findByTradingDateOrderByLeaderRankAsc(date)).thenReturn(List.of(yageo, normal));

        var response = service.getDivergence(date, 10);

        assertThat(response.divergenceCount()).isEqualTo(1);
        assertThat(response.items()).extracting("symbol").containsExactly("2327");
        assertThat(response.safetyNote()).contains("READ_ONLY");
    }
}
