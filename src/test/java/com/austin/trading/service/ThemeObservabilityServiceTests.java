package com.austin.trading.service;

import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ThemeObservabilityServiceTests {

    private final StockThemeMappingRepository mappingRepo = mock(StockThemeMappingRepository.class);
    private final ThemeSnapshotRepository snapshotRepo = mock(ThemeSnapshotRepository.class);
    private final ThemeObservabilityService service = new ThemeObservabilityService(mappingRepo, snapshotRepo);

    @Test
    void taxonomyAggregatesMappingsAndSnapshotsReadOnly() {
        LocalDate date = LocalDate.of(2026, 5, 18);
        when(mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of(
                mapping("2330", "台積電", "AI算力", "AI", "CoWoS", "MANUAL", "1.00", true),
                mapping("3661", "世芯-KY", "AI算力", "AI", "ASIC", "CODEX", "0.80", true),
                mapping("1802", "台玻", "玻纖布", "PCB", null, null, null, false)
        ));
        ThemeSnapshotEntity snapshot = snapshot("AI算力", "AI", "8.50", "7.50", "8.00", "7.00", 1, "2330");
        when(snapshotRepo.findByTradingDateOrderByFinalThemeScoreDesc(date)).thenReturn(List.of(snapshot));

        var response = service.getTaxonomy(date, true);

        assertThat(response.tradingDate()).isEqualTo(date);
        assertThat(response.themeCount()).isEqualTo(1);
        assertThat(response.safetyNote()).contains("READ_ONLY");
        assertThat(response.items()).hasSize(1);
        var item = response.items().get(0);
        assertThat(item.themeTag()).isEqualTo("AI算力");
        assertThat(item.themeCategory()).isEqualTo("AI");
        assertThat(item.activeStockCount()).isEqualTo(2);
        assertThat(item.subThemes()).containsExactly("ASIC", "CoWoS");
        assertThat(item.mappingSources()).containsEntry("MANUAL", 1L).containsEntry("CODEX", 1L);
        assertThat(item.avgConfidence()).isEqualByComparingTo("0.9000");
        assertThat(item.finalThemeScore()).isEqualByComparingTo("8.50");
        assertThat(item.rankingOrder()).isEqualTo(1);

        verify(mappingRepo).findAllByOrderBySymbolAscThemeTagAsc();
        verify(snapshotRepo).findByTradingDateOrderByFinalThemeScoreDesc(date);
        verifyNoMoreInteractions(mappingRepo, snapshotRepo);
    }

    @Test
    void mappingObservabilityReportsQualityIssuesWithoutWriting() {
        when(mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of(
                mapping("2330", "台積電", "AI算力", "AI", null, "MANUAL", "1.00", true),
                mapping("2330", "台積電", "CoWoS", "AI", null, "CODEX", "0.60", true),
                mapping("2368", "金像電", "PCB", null, null, null, null, true),
                mapping("1802", "台玻", "玻纖布", "PCB", null, "CLAUDE", "0.90", false)
        ));

        var response = service.getMappingObservability(null, null, null, null, true, new BigDecimal("0.70"), 50);

        assertThat(response.totalMappings()).isEqualTo(3);
        assertThat(response.activeMappings()).isEqualTo(3);
        assertThat(response.inactiveMappings()).isZero();
        assertThat(response.distinctSymbols()).isEqualTo(2);
        assertThat(response.distinctThemes()).isEqualTo(3);
        assertThat(response.byCategory()).containsEntry("AI", 2L).containsEntry("UNCATEGORIZED", 1L);
        assertThat(response.bySource()).containsEntry("MANUAL", 1L).containsEntry("CODEX", 1L).containsEntry("UNKNOWN_SOURCE", 1L);
        assertThat(response.missingCategoryCount()).isEqualTo(1);
        assertThat(response.missingSourceCount()).isEqualTo(1);
        assertThat(response.missingConfidenceCount()).isEqualTo(1);
        assertThat(response.lowConfidenceCount()).isEqualTo(1);
        assertThat(response.ambiguousSymbolCount()).isEqualTo(1);
        assertThat(response.issues()).extracting("issueType")
                .contains("MISSING_CATEGORY", "MISSING_SOURCE", "MISSING_CONFIDENCE", "LOW_CONFIDENCE", "AMBIGUOUS_SYMBOL");
        assertThat(response.mappings()).hasSize(3);
        assertThat(response.safetyNote()).contains("FinalDecision");

        verify(mappingRepo).findAllByOrderBySymbolAscThemeTagAsc();
        verifyNoMoreInteractions(mappingRepo, snapshotRepo);
    }

    private StockThemeMappingEntity mapping(String symbol,
                                            String stockName,
                                            String themeTag,
                                            String category,
                                            String subTheme,
                                            String source,
                                            String confidence,
                                            boolean active) {
        StockThemeMappingEntity entity = new StockThemeMappingEntity();
        entity.setSymbol(symbol);
        entity.setStockName(stockName);
        entity.setThemeTag(themeTag);
        entity.setThemeCategory(category);
        entity.setSubTheme(subTheme);
        entity.setSource(source);
        entity.setConfidence(confidence == null ? null : new BigDecimal(confidence));
        entity.setIsActive(active);
        return entity;
    }

    private ThemeSnapshotEntity snapshot(String themeTag,
                                         String category,
                                         String finalScore,
                                         String marketScore,
                                         String heatScore,
                                         String continuationScore,
                                         int rank,
                                         String leadingSymbol) {
        ThemeSnapshotEntity entity = new ThemeSnapshotEntity();
        entity.setTradingDate(LocalDate.of(2026, 5, 18));
        entity.setThemeTag(themeTag);
        entity.setThemeCategory(category);
        entity.setFinalThemeScore(new BigDecimal(finalScore));
        entity.setMarketBehaviorScore(new BigDecimal(marketScore));
        entity.setThemeHeatScore(new BigDecimal(heatScore));
        entity.setThemeContinuationScore(new BigDecimal(continuationScore));
        entity.setRankingOrder(rank);
        entity.setLeadingStockSymbol(leadingSymbol);
        return entity;
    }
}
