package com.austin.trading.service;

import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * One-way taxonomy data-quality backfill for existing theme mapping rows.
 *
 * Safety contract: this service only fills missing taxonomy label fields from
 * themeTag. It does not touch FinalDecision, candidate ranking, risk/price
 * gates, capital sizing, or any BUY/SELL/ENTER/WAIT/REST semantics.
 */
@Service
public class ThemeTaxonomyBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ThemeTaxonomyBackfillService.class);

    private final StockThemeMappingRepository mappingRepo;
    private final ThemeSnapshotRepository snapshotRepo;

    @Value("${trading.theme-taxonomy.startup-backfill.enabled:true}")
    private boolean startupBackfillEnabled;

    public ThemeTaxonomyBackfillService(StockThemeMappingRepository mappingRepo,
                                        ThemeSnapshotRepository snapshotRepo) {
        this.mappingRepo = mappingRepo;
        this.snapshotRepo = snapshotRepo;
    }

    @PostConstruct
    public void backfillOnStartupIfEnabled() {
        if (!startupBackfillEnabled) {
            log.info("[ThemeTaxonomyBackfill] startup backfill disabled");
            return;
        }
        try {
            int updated = backfillMissingCategoriesAndSources();
            if (updated > 0) {
                log.info("[ThemeTaxonomyBackfill] filled missing theme taxonomy fields: rows={}", updated);
            } else {
                log.info("[ThemeTaxonomyBackfill] no missing theme taxonomy fields to fill");
            }
        } catch (Exception e) {
            log.warn("[ThemeTaxonomyBackfill] failed; startup continues: {}", e.getMessage(), e);
        }
    }

    /**
     * Fill blank themeCategory/source values and refine resolvable generic OTHER rows.
     * Existing non-blank, non-OTHER categories/sources are preserved so manual labels remain the source of truth.
     * Generic OTHER rows are only changed when the deterministic review classifier has a non-UNRESOLVED suggestion.
     *
     * @return number of changed rows across stock_theme_mapping and theme_snapshot
     */
    @Transactional
    public int backfillMissingCategoriesAndSources() {
        int updatedMappings = backfillMappings();
        int updatedSnapshots = backfillSnapshots();
        return updatedMappings + updatedSnapshots;
    }

    int backfillMappings() {
        List<StockThemeMappingEntity> changed = new ArrayList<>();
        for (StockThemeMappingEntity mapping : mappingRepo.findAllByOrderBySymbolAscThemeTagAsc()) {
            boolean dirty = false;
            if (!hasText(mapping.getThemeCategory())) {
                mapping.setThemeCategory(ThemeTaxonomyClassifier.classify(mapping.getThemeTag()));
                dirty = true;
            }
            if (isOtherCategory(mapping)) {
                String suggestion = ThemeTaxonomyClassifier.suggestCategoryForGenericOther(mapping.getSymbol(), mapping.getStockName());
                if (hasText(suggestion) && !ThemeTaxonomyClassifier.UNRESOLVED_OTHER.equals(suggestion)) {
                    mapping.setThemeCategory(suggestion);
                    dirty = true;
                }
            }
            if (!hasText(mapping.getSource())) {
                mapping.setSource(ThemeTaxonomyClassifier.inferLegacySource(mapping.getThemeTag()));
                dirty = true;
            }
            if (dirty) {
                changed.add(mapping);
            }
        }
        if (!changed.isEmpty()) {
            mappingRepo.saveAll(changed);
        }
        return changed.size();
    }

    int backfillSnapshots() {
        List<ThemeSnapshotEntity> changed = new ArrayList<>();
        for (ThemeSnapshotEntity snapshot : snapshotRepo.findAll()) {
            if (!hasText(snapshot.getThemeCategory())) {
                snapshot.setThemeCategory(ThemeTaxonomyClassifier.classify(snapshot.getThemeTag()));
                changed.add(snapshot);
            }
        }
        if (!changed.isEmpty()) {
            snapshotRepo.saveAll(changed);
        }
        return changed.size();
    }

    private static boolean isOtherCategory(StockThemeMappingEntity mapping) {
        return hasText(mapping.getThemeCategory())
                && ThemeTaxonomyClassifier.OTHER.equalsIgnoreCase(mapping.getThemeCategory().trim());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
