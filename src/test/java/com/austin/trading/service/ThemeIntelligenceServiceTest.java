package com.austin.trading.service;

import com.austin.trading.dto.response.ThemeContextSnapshot;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import com.austin.trading.repository.ThemeLifecycleStateRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThemeIntelligenceServiceTest {

    private final ThemeSnapshotRepository themeSnapshotRepository = mock(ThemeSnapshotRepository.class);
    private final ThemeLifecycleStateRepository lifecycleRepository = mock(ThemeLifecycleStateRepository.class);
    private final KolThemeSignalDailySnapshotRepository kolRepository = mock(KolThemeSignalDailySnapshotRepository.class);
    private final ThemeIntelligenceService service = new ThemeIntelligenceService(
            themeSnapshotRepository,
            lifecycleRepository,
            kolRepository,
            new ThemeLifecycleResolver()
    );

    @Test
    void summaryToleratesMissingLifecycleAndKolDates() {
        LocalDate date = LocalDate.now(java.time.ZoneId.of("Asia/Taipei"));
        ThemeSnapshotEntity snapshot = snapshot(date, "AI_SERVER", "8.0");

        when(themeSnapshotRepository.countFutureRows(any())).thenReturn(0L);
        when(themeSnapshotRepository.countFutureRowsForTheme(any(), any())).thenReturn(0L);
        when(themeSnapshotRepository.findLatestValidTradingDate(any())).thenReturn(date);
        when(themeSnapshotRepository.findLatestValidTradingDateForTheme("AI_SERVER", date)).thenReturn(date);
        when(lifecycleRepository.findAll()).thenReturn(List.of());
        when(kolRepository.findAll()).thenReturn(List.of());
        when(themeSnapshotRepository.findByTradingDateOrderByFinalThemeScoreDesc(date)).thenReturn(List.of(snapshot));
        when(themeSnapshotRepository.findByTradingDateAndThemeTag(date, "AI_SERVER")).thenReturn(Optional.of(snapshot));
        when(lifecycleRepository.findByTradingDateAndThemeTag(date, "AI_SERVER")).thenReturn(Optional.empty());
        when(kolRepository.findByTradingDateAndThemeTag(date, "AI_SERVER")).thenReturn(List.of());

        List<ThemeContextSnapshot> result = service.summary();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).themeName()).isEqualTo("AI_SERVER");
        assertThat(result.get(0).themeLifecycle()).isEqualTo("MAINSTREAM");
        assertThat(result.get(0).themeStillActive()).isTrue();
        assertThat(result.get(0).productionDecisionAllowed()).isFalse();
        assertThat(result.get(0).manualConfirmRequired()).isTrue();
    }

    @Test
    void futureThemeDataIsNotUsedAsLatestSummary() {
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Taipei"));
        LocalDate future = today.plusYears(8);
        ThemeSnapshotEntity valid = snapshot(today, "VALID_THEME", "7.1");
        ThemeSnapshotEntity futureRow = snapshot(future, "FUTURE_THEME", "9.9");

        when(themeSnapshotRepository.countFutureRows(any())).thenReturn(1L);
        when(themeSnapshotRepository.countFutureRowsForTheme(any(), any())).thenReturn(0L);
        when(themeSnapshotRepository.findLatestTradingDate()).thenReturn(future);
        when(themeSnapshotRepository.findLatestValidTradingDate(any())).thenReturn(today);
        when(themeSnapshotRepository.findLatestValidTradingDateForTheme("VALID_THEME", today)).thenReturn(today);
        when(lifecycleRepository.findAll()).thenReturn(List.of());
        when(kolRepository.findAll()).thenReturn(List.of());
        when(themeSnapshotRepository.findByTradingDateOrderByFinalThemeScoreDesc(today)).thenReturn(List.of(valid));
        when(themeSnapshotRepository.findByTradingDateOrderByFinalThemeScoreDesc(future)).thenReturn(List.of(futureRow));
        when(themeSnapshotRepository.findByTradingDateAndThemeTag(today, "VALID_THEME")).thenReturn(Optional.of(valid));
        when(lifecycleRepository.findByTradingDateAndThemeTag(today, "VALID_THEME")).thenReturn(Optional.empty());
        when(kolRepository.findByTradingDateAndThemeTag(today, "VALID_THEME")).thenReturn(List.of());

        List<ThemeContextSnapshot> result = service.summary();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).themeName()).isEqualTo("VALID_THEME");
        assertThat(result.get(0).tradingDate()).isEqualTo(today);
        assertThat(result.get(0).dataStatus()).isEqualTo("FUTURE_DATA_DETECTED");
        assertThat(result.get(0).futureDataDetected()).isTrue();
        assertThat(result.get(0).latestValidTradingDate()).isEqualTo(today);
    }

    private ThemeSnapshotEntity snapshot(LocalDate date, String theme, String score) {
        ThemeSnapshotEntity snapshot = new ThemeSnapshotEntity();
        snapshot.setTradingDate(date);
        snapshot.setThemeTag(theme);
        snapshot.setThemeHeatScore(new BigDecimal("8.2"));
        snapshot.setFinalThemeScore(new BigDecimal(score));
        snapshot.setThemeContinuationScore(new BigDecimal("7.5"));
        snapshot.setLeadingStockSymbol("2330");
        return snapshot;
    }
}
