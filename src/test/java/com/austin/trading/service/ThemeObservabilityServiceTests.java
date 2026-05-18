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
                mapping("1802", "台玻", "玻纖布", "PCB", null, "CLAUDE", "0.90", false),
                mapping("1216", "統一", "其他強勢股", "OTHER", null, "CODEX", "1.00", true)
        ));

        var response = service.getMappingObservability(null, null, null, null, true, new BigDecimal("0.70"), 50, null, null);

        assertThat(response.totalMappings()).isEqualTo(4);
        assertThat(response.activeMappings()).isEqualTo(4);
        assertThat(response.inactiveMappings()).isZero();
        assertThat(response.distinctSymbols()).isEqualTo(3);
        assertThat(response.distinctThemes()).isEqualTo(4);
        assertThat(response.byCategory()).containsEntry("AI", 2L).containsEntry("UNCATEGORIZED", 1L).containsEntry("OTHER", 1L);
        assertThat(response.bySource()).containsEntry("MANUAL", 1L).containsEntry("CODEX", 2L).containsEntry("UNKNOWN_SOURCE", 1L);
        assertThat(response.missingCategoryCount()).isEqualTo(1);
        assertThat(response.missingSourceCount()).isEqualTo(1);
        assertThat(response.missingConfidenceCount()).isEqualTo(1);
        assertThat(response.lowConfidenceCount()).isEqualTo(1);
        assertThat(response.ambiguousSymbolCount()).isEqualTo(1);
        assertThat(response.otherCategoryCount()).isEqualTo(1);
        assertThat(response.otherCategoryRatio()).isEqualByComparingTo("0.2500");
        assertThat(response.otherBySuggestedCategory()).containsEntry(ThemeTaxonomyClassifier.CONSUMER, 1L);
        assertThat(response.resolvableOtherCategoryCount()).isEqualTo(1);
        assertThat(response.unresolvedOtherCategoryCount()).isZero();
        assertThat(response.otherCategorySuggestions()).hasSize(1);
        assertThat(response.otherCategorySuggestions().get(0).suggestedCategory()).isEqualTo(ThemeTaxonomyClassifier.CONSUMER);
        assertThat(response.taxonomyQualityStatus()).isEqualTo("DATA_GAP");
        assertThat(response.taxonomyQualitySummary()).contains("required metadata gaps");
        assertThat(response.qualityWarnings())
                .contains("MISSING_CATEGORY=1", "MISSING_SOURCE=1", "MISSING_CONFIDENCE=1", "LOW_CONFIDENCE=1", "AMBIGUOUS_SYMBOL=1", "RESOLVABLE_OTHER=1");
        assertThat(response.byIssueType())
                .containsEntry("MISSING_CATEGORY", 1L)
                .containsEntry("MISSING_SOURCE", 1L)
                .containsEntry("MISSING_CONFIDENCE", 1L)
                .containsEntry("LOW_CONFIDENCE", 1L)
                .containsEntry("AMBIGUOUS_SYMBOL", 2L)
                .containsEntry("OTHER_CATEGORY_REVIEW", 1L);
        assertThat(response.issues()).extracting("issueType")
                .contains("MISSING_CATEGORY", "MISSING_SOURCE", "MISSING_CONFIDENCE", "LOW_CONFIDENCE", "AMBIGUOUS_SYMBOL", "OTHER_CATEGORY_REVIEW");
        assertThat(response.mappings()).hasSize(4);
        assertThat(response.safetyNote()).contains("FinalDecision");

        verify(mappingRepo).findAllByOrderBySymbolAscThemeTagAsc();
        verifyNoMoreInteractions(mappingRepo, snapshotRepo);
    }

    @Test
    void mappingObservabilityReturnsFullIssueCountsEvenWhenIssueListIsLimited() {
        when(mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of(
                mapping("1216", "統一", "其他強勢股", "OTHER", null, "CODEX", "1.00", true),
                mapping("1319", "東陽", "其他強勢股", "OTHER", null, "CODEX", "1.00", true),
                mapping("2368", "金像電", "PCB", null, null, null, null, true)
        ));

        var response = service.getMappingObservability(null, null, null, null, true, new BigDecimal("0.70"), 1, null, null);

        assertThat(response.issues()).hasSize(1);
        assertThat(response.byIssueType())
                .containsEntry("OTHER_CATEGORY_REVIEW", 2L)
                .containsEntry("MISSING_CATEGORY", 1L)
                .containsEntry("MISSING_SOURCE", 1L)
                .containsEntry("MISSING_CONFIDENCE", 1L);
        assertThat(response.otherCategoryCount()).isEqualTo(2);
        assertThat(response.otherCategoryRatio()).isEqualByComparingTo("0.6667");
        assertThat(response.otherCategorySuggestions()).hasSize(1);
        assertThat(response.otherBySuggestedCategory())
                .containsEntry(ThemeTaxonomyClassifier.CONSUMER, 1L)
                .containsEntry(ThemeTaxonomyClassifier.MATERIALS, 1L);
        assertThat(response.resolvableOtherCategoryCount()).isEqualTo(2);
        assertThat(response.unresolvedOtherCategoryCount()).isZero();

        verify(mappingRepo).findAllByOrderBySymbolAscThemeTagAsc();
        verifyNoMoreInteractions(mappingRepo, snapshotRepo);
    }

    @Test
    void mappingObservabilitySummarizesUnresolvedOtherSuggestionsWithoutChangingStoredCategory() {
        when(mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of(
                mapping("9999", "未知公司", "其他強勢股", "OTHER", null, "CODEX", "1.00", true)
        ));

        var response = service.getMappingObservability(null, null, null, null, true, new BigDecimal("0.70"), 10,
                null, null);

        assertThat(response.otherCategoryCount()).isEqualTo(1);
        assertThat(response.resolvableOtherCategoryCount()).isZero();
        assertThat(response.unresolvedOtherCategoryCount()).isEqualTo(1);
        assertThat(response.taxonomyQualityStatus()).isEqualTo("MANUAL_REVIEW");
        assertThat(response.taxonomyQualitySummary()).contains("manual review");
        assertThat(response.qualityWarnings()).containsExactly("UNRESOLVED_OTHER_MANUAL_REVIEW=1");
        assertThat(response.otherBySuggestedCategory()).containsEntry(ThemeTaxonomyClassifier.UNRESOLVED_OTHER, 1L);
        assertThat(response.otherCategorySuggestions()).hasSize(1);
        var suggestion = response.otherCategorySuggestions().get(0);
        assertThat(suggestion.currentCategory()).isEqualTo("OTHER");
        assertThat(suggestion.suggestedCategory()).isEqualTo(ThemeTaxonomyClassifier.UNRESOLVED_OTHER);
        assertThat(suggestion.reason()).contains("keep in OTHER review queue");

        verify(mappingRepo).findAllByOrderBySymbolAscThemeTagAsc();
        verifyNoMoreInteractions(mappingRepo, snapshotRepo);
    }

    @Test
    void mappingObservabilityCanDrillDownOtherSuggestionsBySuggestedCategory() {
        when(mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of(
                mapping("1216", "統一", "其他強勢股", "OTHER", null, "CODEX", "1.00", true),
                mapping("1319", "東陽", "其他強勢股", "OTHER", null, "CODEX", "1.00", true),
                mapping("2330", "台積電", "AI算力", "AI_COMPUTE", null, "CODEX", "1.00", true)
        ));

        var response = service.getMappingObservability(null, null, null, null, true, new BigDecimal("0.70"), 10,
                ThemeTaxonomyClassifier.MATERIALS, null);

        assertThat(response.totalMappings()).isEqualTo(1);
        assertThat(response.otherCategoryCount()).isEqualTo(1);
        assertThat(response.otherBySuggestedCategory()).containsOnlyKeys(ThemeTaxonomyClassifier.MATERIALS);
        assertThat(response.otherCategorySuggestions()).hasSize(1);
        assertThat(response.otherCategorySuggestions().get(0).symbol()).isEqualTo("1319");
        assertThat(response.otherCategorySuggestions().get(0).suggestedCategory()).isEqualTo(ThemeTaxonomyClassifier.MATERIALS);
        assertThat(response.taxonomyQualityStatus()).isEqualTo("REFINEMENT_READY");
        assertThat(response.qualityWarnings()).containsExactly("RESOLVABLE_OTHER=1");
        assertThat(response.byIssueType()).containsEntry("OTHER_CATEGORY_REVIEW", 1L);

        verify(mappingRepo).findAllByOrderBySymbolAscThemeTagAsc();
        verifyNoMoreInteractions(mappingRepo, snapshotRepo);
    }

    @Test
    void mappingObservabilityCanDrillDownUnresolvedOtherQueue() {
        when(mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of(
                mapping("1216", "統一", "其他強勢股", "OTHER", null, "CODEX", "1.00", true),
                mapping("9999", "未知公司", "其他強勢股", "OTHER", null, "CODEX", "1.00", true),
                mapping("2330", "台積電", "AI算力", "AI_COMPUTE", null, "CODEX", "1.00", true)
        ));

        var response = service.getMappingObservability(null, null, null, null, true, new BigDecimal("0.70"), 10,
                null, true);

        assertThat(response.totalMappings()).isEqualTo(1);
        assertThat(response.otherCategoryCount()).isEqualTo(1);
        assertThat(response.resolvableOtherCategoryCount()).isZero();
        assertThat(response.unresolvedOtherCategoryCount()).isEqualTo(1);
        assertThat(response.otherCategorySuggestions()).hasSize(1);
        assertThat(response.otherCategorySuggestions().get(0).symbol()).isEqualTo("9999");
        assertThat(response.otherCategorySuggestions().get(0).suggestedCategory()).isEqualTo(ThemeTaxonomyClassifier.UNRESOLVED_OTHER);

        verify(mappingRepo).findAllByOrderBySymbolAscThemeTagAsc();
        verifyNoMoreInteractions(mappingRepo, snapshotRepo);
    }

    @Test
    void mappingObservabilityReportsOkWhenNoQualityWarningsRemain() {
        when(mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of(
                mapping("2330", "台積電", "AI算力", "AI_COMPUTE", null, "MANUAL", "1.00", true),
                mapping("3661", "世芯-KY", "ASIC", "AI_COMPUTE", null, "CODEX", "0.90", true)
        ));

        var response = service.getMappingObservability(null, null, null, null, true, new BigDecimal("0.70"), 10,
                null, null);

        assertThat(response.taxonomyQualityStatus()).isEqualTo("OK");
        assertThat(response.taxonomyQualitySummary()).contains("clean");
        assertThat(response.qualityWarnings()).isEmpty();
        assertThat(response.byIssueType()).isEmpty();

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
