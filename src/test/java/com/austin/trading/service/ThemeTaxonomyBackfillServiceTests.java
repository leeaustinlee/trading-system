package com.austin.trading.service;

import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ThemeTaxonomyBackfillServiceTests {

    private final StockThemeMappingRepository mappingRepo = mock(StockThemeMappingRepository.class);
    private final ThemeSnapshotRepository snapshotRepo = mock(ThemeSnapshotRepository.class);
    private final ThemeTaxonomyBackfillService service = new ThemeTaxonomyBackfillService(mappingRepo, snapshotRepo);

    @Test
    void classifierMapsKnownThemeTagsToCanonicalCategories() {
        assertThat(ThemeTaxonomyClassifier.classify("AI伺服器/電腦週邊")).isEqualTo(ThemeTaxonomyClassifier.AI_COMPUTE);
        assertThat(ThemeTaxonomyClassifier.classify("AI_CHIP_14313")).isEqualTo(ThemeTaxonomyClassifier.AI_COMPUTE);
        assertThat(ThemeTaxonomyClassifier.classify("PCB/載板/材料")).isEqualTo(ThemeTaxonomyClassifier.PCB);
        assertThat(ThemeTaxonomyClassifier.classify("記憶體/儲存")).isEqualTo(ThemeTaxonomyClassifier.MEMORY);
        assertThat(ThemeTaxonomyClassifier.classify("散熱/機構")).isEqualTo(ThemeTaxonomyClassifier.COOLING);
        assertThat(ThemeTaxonomyClassifier.classify("金融")).isEqualTo(ThemeTaxonomyClassifier.FINANCIAL);
        assertThat(ThemeTaxonomyClassifier.classify("其他強勢股")).isEqualTo(ThemeTaxonomyClassifier.OTHER);
        assertThat(ThemeTaxonomyClassifier.classify(null)).isEqualTo(ThemeTaxonomyClassifier.UNKNOWN);
        assertThat(ThemeTaxonomyClassifier.inferLegacySource("AI_CHIP_14313"))
                .isEqualTo(ThemeTaxonomyClassifier.LEGACY_AI_CHIP_SEED_SOURCE);
        assertThat(ThemeTaxonomyClassifier.inferLegacySource("其他強勢股"))
                .isEqualTo(ThemeTaxonomyClassifier.LEGACY_THEME_MAPPING_SOURCE);
    }

    @Test
    void backfillFillsOnlyMissingTaxonomyFieldsAndPreservesManualLabels() {
        StockThemeMappingEntity missingMapping = mapping("2330", "AI_CHIP_14313", null, null);
        StockThemeMappingEntity missingSourceOnly = mapping("2327", "其他強勢股", "OTHER", " ");
        StockThemeMappingEntity manualMapping = mapping("2368", "PCB/載板/材料", "MANUAL_PCB", "manual-curation");
        ThemeSnapshotEntity missingSnapshot = snapshot("金融", "");
        ThemeSnapshotEntity manualSnapshot = snapshot("散熱/機構", "MANUAL_COOLING");
        when(mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of(missingMapping, missingSourceOnly, manualMapping));
        when(snapshotRepo.findAll()).thenReturn(List.of(missingSnapshot, manualSnapshot));

        int updated = service.backfillMissingCategoriesAndSources();

        assertThat(updated).isEqualTo(3);
        assertThat(missingMapping.getThemeCategory()).isEqualTo(ThemeTaxonomyClassifier.AI_COMPUTE);
        assertThat(missingMapping.getSource()).isEqualTo(ThemeTaxonomyClassifier.LEGACY_AI_CHIP_SEED_SOURCE);
        assertThat(missingSourceOnly.getThemeCategory()).isEqualTo("OTHER");
        assertThat(missingSourceOnly.getSource()).isEqualTo(ThemeTaxonomyClassifier.LEGACY_THEME_MAPPING_SOURCE);
        assertThat(manualMapping.getThemeCategory()).isEqualTo("MANUAL_PCB");
        assertThat(manualMapping.getSource()).isEqualTo("manual-curation");
        assertThat(missingSnapshot.getThemeCategory()).isEqualTo(ThemeTaxonomyClassifier.FINANCIAL);
        assertThat(manualSnapshot.getThemeCategory()).isEqualTo("MANUAL_COOLING");
        verify(mappingRepo).findAllByOrderBySymbolAscThemeTagAsc();
        verify(mappingRepo).saveAll(List.of(missingMapping, missingSourceOnly));
        verify(snapshotRepo).findAll();
        verify(snapshotRepo).saveAll(List.of(missingSnapshot));
        verifyNoMoreInteractions(mappingRepo, snapshotRepo);
    }

    @Test
    void backfillRefinesResolvableOtherRowsButKeepsUnresolvedOtherInReviewQueue() {
        StockThemeMappingEntity resolvableOther = mapping("1319", "其他強勢股", "OTHER", "codex-v2-postmarket");
        resolvableOther.setStockName("東陽");
        StockThemeMappingEntity unresolvedOther = mapping("9999", "其他強勢股", "OTHER", "codex-v2-postmarket");
        unresolvedOther.setStockName("未知公司");
        StockThemeMappingEntity manualCategory = mapping("2330", "AI算力", "AI_COMPUTE", "manual-curation");
        manualCategory.setStockName("台積電");
        when(mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of(resolvableOther, unresolvedOther, manualCategory));
        when(snapshotRepo.findAll()).thenReturn(List.of());

        int updated = service.backfillMissingCategoriesAndSources();

        assertThat(updated).isEqualTo(1);
        assertThat(resolvableOther.getThemeCategory()).isEqualTo(ThemeTaxonomyClassifier.MATERIALS);
        assertThat(resolvableOther.getSource()).isEqualTo("codex-v2-postmarket");
        assertThat(unresolvedOther.getThemeCategory()).isEqualTo(ThemeTaxonomyClassifier.OTHER);
        assertThat(manualCategory.getThemeCategory()).isEqualTo("AI_COMPUTE");
        verify(mappingRepo).findAllByOrderBySymbolAscThemeTagAsc();
        verify(mappingRepo).saveAll(List.of(resolvableOther));
        verify(snapshotRepo).findAll();
        verify(snapshotRepo, never()).saveAll(anyList());
        verifyNoMoreInteractions(mappingRepo, snapshotRepo);
    }

    @Test
    void backfillCanClassifyBlankGenericOtherAndRefineItInSameRun() {
        StockThemeMappingEntity blankResolvableOther = mapping("1216", "其他強勢股", null, "codex-v2-postmarket");
        blankResolvableOther.setStockName("統一");
        when(mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of(blankResolvableOther));
        when(snapshotRepo.findAll()).thenReturn(List.of());

        int updated = service.backfillMissingCategoriesAndSources();

        assertThat(updated).isEqualTo(1);
        assertThat(blankResolvableOther.getThemeCategory()).isEqualTo(ThemeTaxonomyClassifier.CONSUMER);
        verify(mappingRepo).findAllByOrderBySymbolAscThemeTagAsc();
        verify(mappingRepo).saveAll(List.of(blankResolvableOther));
        verify(snapshotRepo).findAll();
        verify(snapshotRepo, never()).saveAll(anyList());
        verifyNoMoreInteractions(mappingRepo, snapshotRepo);
    }

    @Test
    void backfillIsNoOpWhenAllCategoriesExist() {
        StockThemeMappingEntity mapping = mapping("2368", "PCB/載板/材料", "PCB", "codex-v2-postmarket");
        ThemeSnapshotEntity snapshot = snapshot("金融", "FINANCIAL");
        when(mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of(mapping));
        when(snapshotRepo.findAll()).thenReturn(List.of(snapshot));

        int updated = service.backfillMissingCategoriesAndSources();

        assertThat(updated).isZero();
        verify(mappingRepo).findAllByOrderBySymbolAscThemeTagAsc();
        verify(mappingRepo, never()).saveAll(anyList());
        verify(snapshotRepo).findAll();
        verify(snapshotRepo, never()).saveAll(anyList());
        verifyNoMoreInteractions(mappingRepo, snapshotRepo);
    }

    private StockThemeMappingEntity mapping(String symbol, String themeTag, String category, String source) {
        StockThemeMappingEntity entity = new StockThemeMappingEntity();
        entity.setSymbol(symbol);
        entity.setThemeTag(themeTag);
        entity.setThemeCategory(category);
        entity.setSource(source);
        entity.setIsActive(true);
        return entity;
    }

    private ThemeSnapshotEntity snapshot(String themeTag, String category) {
        ThemeSnapshotEntity entity = new ThemeSnapshotEntity();
        entity.setThemeTag(themeTag);
        entity.setThemeCategory(category);
        return entity;
    }
}
