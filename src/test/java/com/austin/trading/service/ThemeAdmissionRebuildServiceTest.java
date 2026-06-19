package com.austin.trading.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ThemeAdmissionRebuildServiceTest {

    private final ThemeAdmissionShadowService shadowService = mock(ThemeAdmissionShadowService.class);
    private final ThemeAdmissionWriteService writeService = mock(ThemeAdmissionWriteService.class);

    @Test
    void writeFalseRunsShadowOnlyAndDoesNotWriteProductionTables() {
        LocalDate date = LocalDate.of(2026, 6, 18);
        when(shadowService.rebuildForDate(date)).thenReturn(3);
        ThemeAdmissionRebuildService service = new ThemeAdmissionRebuildService(shadowService, writeService, true, false);

        var result = service.rebuild(date, date, false);

        assertThat(result.shadowRows()).isEqualTo(3);
        assertThat(result.shadowOnly()).isTrue();
        assertThat(result.productionBuyImpact()).isFalse();
        verifyNoInteractions(writeService);
    }

    @Test
    void writeTrueFeatureFlagFalseRejectsBeforeAnyWrite() {
        LocalDate date = LocalDate.of(2026, 6, 18);
        ThemeAdmissionRebuildService service = new ThemeAdmissionRebuildService(shadowService, writeService, false, false);

        assertThatThrownBy(() -> service.rebuild(date, date, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("write-enabled=false");
        verifyNoInteractions(shadowService, writeService);
    }

    @Test
    void writeTrueBuyImpactFlagTrueRejectsAsP1ARegressionGuard() {
        LocalDate date = LocalDate.of(2026, 6, 18);
        ThemeAdmissionRebuildService service = new ThemeAdmissionRebuildService(shadowService, writeService, true, true);

        assertThatThrownBy(() -> service.rebuild(date, date, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("buy-impact-enabled must remain false");
        verifyNoInteractions(shadowService, writeService);
    }
}
