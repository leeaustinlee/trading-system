package com.austin.trading.service;

import com.austin.trading.dto.internal.ThemeStrengthDecision;
import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import com.austin.trading.repository.ThemeStrengthDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1.1 Theme Strength Layer — persistence + round-trip integration tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class ThemeStrengthServiceIntegrationTests {

    @Autowired ThemeStrengthService             service;
    @Autowired ThemeSnapshotRepository          snapshotRepo;
    @Autowired StockThemeMappingRepository      mappingRepo;
    @Autowired ThemeStrengthDecisionRepository  decisionRepo;

    private LocalDate uniqueDate;

    @BeforeEach
    void setUp() {
        uniqueDate = LocalDate.of(2007, 1, 1).plusDays(System.nanoTime() % 10_000);
    }

    private ThemeSnapshotEntity snapshot(String tag, double mb, double heat, double cont) {
        ThemeSnapshotEntity e = new ThemeSnapshotEntity();
        e.setTradingDate(uniqueDate);
        e.setThemeTag(tag);
        e.setMarketBehaviorScore(BigDecimal.valueOf(mb));
        e.setThemeHeatScore(BigDecimal.valueOf(heat));
        e.setThemeContinuationScore(BigDecimal.valueOf(cont));
        e.setAvgGainPct(BigDecimal.valueOf(1.5));
        e.setStrongStockCount(3);
        e.setFinalThemeScore(BigDecimal.valueOf(mb));
        return snapshotRepo.save(e);
    }

    // ── evaluateAll ───────────────────────────────────────────────────────────

    @Test
    void evaluateAll_noSnapshots_returnsEmptyMap() {
        Map<String, ThemeStrengthDecision> result = service.evaluateAll(uniqueDate, null);
        assertThat(result).isEmpty();
    }

    @Test
    void evaluateAll_persistsAndReturnsDecisions() {
        snapshot("AI_CHIP", 8.0, 8.0, 7.5);
        snapshot("SOLAR",   3.0, 3.0, 3.0);

        Map<String, ThemeStrengthDecision> result = service.evaluateAll(uniqueDate, null);

        assertThat(result).containsKey("AI_CHIP").containsKey("SOLAR");
        assertThat(decisionRepo.findByTradingDateOrderByStrengthScoreDesc(uniqueDate))
                .hasSize(2);
    }

    @Test
    void evaluateAll_strongTheme_isTradable() {
        snapshot("STRONG_THEME", 8.5, 9.0, 8.0);

        Map<String, ThemeStrengthDecision> result = service.evaluateAll(uniqueDate, null);

        assertThat(result.get("STRONG_THEME").tradable()).isTrue();
    }

    @Test
    void evaluateAll_weakTheme_notTradable() {
        snapshot("WEAK_THEME", 1.0, 1.0, 1.0);

        Map<String, ThemeStrengthDecision> result = service.evaluateAll(uniqueDate, null);

        assertThat(result.get("WEAK_THEME").tradable()).isFalse();
    }

    // ── findForSymbol ─────────────────────────────────────────────────────────

    @Test
    void findForSymbol_noMapping_returnsEmpty() {
        Optional<ThemeStrengthDecision> d = service.findForSymbol("NOMAPPING", uniqueDate);
        assertThat(d).isEmpty();
    }

    @Test
    void findForSymbol_withMapping_returnsDecision() {
        // Use a unique themeTag and symbol derived from uniqueDate to avoid duplicate-key across runs
        String tag    = "AI_CHIP_" + uniqueDate.toEpochDay();
        String symbol = "SYM_"     + (uniqueDate.toEpochDay() % 10_000);

        snapshot(tag, 8.0, 8.0, 7.0);
        service.evaluateAll(uniqueDate, null);

        StockThemeMappingEntity mapping = new StockThemeMappingEntity();
        mapping.setSymbol(symbol);
        mapping.setThemeTag(tag);
        mapping.setIsActive(true);
        mappingRepo.save(mapping);

        Optional<ThemeStrengthDecision> d = service.findForSymbol(symbol, uniqueDate);
        assertThat(d).isPresent();
        assertThat(d.get().themeTag()).isEqualTo(tag);
    }
}
