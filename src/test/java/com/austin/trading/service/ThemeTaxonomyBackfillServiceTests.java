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
    }

    @Test
    void backfillFillsOnlyMissingCategoriesAndPreservesManualLabels() {
        StockThemeMappingEntity missingMapping = mapping("2330", "AI_CHIP_14313", null);
        StockThemeMappingEntity manualMapping = mapping("2368", "PCB/載板/材料", "MANUAL_PCB");
        ThemeSnapshotEntity missingSnapshot = snapshot("金融", "");
        ThemeSnapshotEntity manualSnapshot = snapshot("散熱/機構", "MANUAL_COOLING");
        when(mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of(missingMapping, manualMapping));
        when(snapshotRepo.findAll()).thenReturn(List.of(missingSnapshot, manualSnapshot));

        int updated = service.backfillMissingCategories();

        assertThat(updated).isEqualTo(2);
        assertThat(missingMapping.getThemeCategory()).isEqualTo(ThemeTaxonomyClassifier.AI_COMPUTE);
        assertThat(manualMapping.getThemeCategory()).isEqualTo("MANUAL_PCB");
        assertThat(missingSnapshot.getThemeCategory()).isEqualTo(ThemeTaxonomyClassifier.FINANCIAL);
        assertThat(manualSnapshot.getThemeCategory()).isEqualTo("MANUAL_COOLING");
        verify(mappingRepo).findAllByOrderBySymbolAscThemeTagAsc();
        verify(mappingRepo).saveAll(List.of(missingMapping));
        verify(snapshotRepo).findAll();
        verify(snapshotRepo).saveAll(List.of(missingSnapshot));
        verifyNoMoreInteractions(mappingRepo, snapshotRepo);
    }

    @Test
    void backfillIsNoOpWhenAllCategoriesExist() {
        StockThemeMappingEntity mapping = mapping("2368", "PCB/載板/材料", "PCB");
        ThemeSnapshotEntity snapshot = snapshot("金融", "FINANCIAL");
        when(mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()).thenReturn(List.of(mapping));
        when(snapshotRepo.findAll()).thenReturn(List.of(snapshot));

        int updated = service.backfillMissingCategories();

        assertThat(updated).isZero();
        verify(mappingRepo).findAllByOrderBySymbolAscThemeTagAsc();
        verify(mappingRepo, never()).saveAll(anyList());
        verify(snapshotRepo).findAll();
        verify(snapshotRepo, never()).saveAll(anyList());
        verifyNoMoreInteractions(mappingRepo, snapshotRepo);
    }

    private StockThemeMappingEntity mapping(String symbol, String themeTag, String category) {
        StockThemeMappingEntity entity = new StockThemeMappingEntity();
        entity.setSymbol(symbol);
        entity.setThemeTag(themeTag);
        entity.setThemeCategory(category);
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
