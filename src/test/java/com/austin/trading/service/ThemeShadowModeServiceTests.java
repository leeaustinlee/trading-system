package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import com.austin.trading.domain.enums.DecisionDiffType;
import com.austin.trading.dto.internal.GateTraceRecordDto;
import com.austin.trading.dto.internal.GateTraceRecordDto.Result;
import com.austin.trading.dto.internal.ThemeGateTraceResultDto;
import com.austin.trading.entity.ThemeShadowDecisionLogEntity;
import com.austin.trading.repository.ThemeShadowDecisionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * v2 Theme Engine PR5：ThemeShadowModeService diff 分類 + 落 log 測試。
 */
class ThemeShadowModeServiceTests {

    private ThemeSnapshotProperties props;
    private ThemeShadowDecisionLogRepository logRepo;
    private ThemeShadowModeService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 24);

    @BeforeEach
    void setUp() {
        props = mock(ThemeSnapshotProperties.class);
        logRepo = mock(ThemeShadowDecisionLogRepository.class);
        when(props.shadowModeEnabled()).thenReturn(true);
        when(logRepo.findByTradingDateAndSymbol(any(), any())).thenReturn(Optional.empty());
        when(logRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new ThemeShadowModeService(props, logRepo);
    }

    // ── flag-off ─────────────────────────────────────────────────────

    @Test
    void flagDisabled_skipsAllWork() {
        when(props.shadowModeEnabled()).thenReturn(false);
        var result = service.record(TODAY, "BULL_TREND",
                List.of(input("2454", "ENTER", new BigDecimal("8.0"), trace(Result.PASS, new BigDecimal("8.1")))));
        assertThat(result.active()).isFalse();
        verifyNoInteractions(logRepo);
    }

    @Test
    void emptyInputs_returnsEmptyResult() {
        var result = service.record(TODAY, "BULL_TREND", List.of());
        assertThat(result.totalRecorded()).isZero();
        verifyNoInteractions(logRepo);
    }

    // ── 6 分類 case ───────────────────────────────────────────────────

    @Test
    void classify_sameBuy_legacyEnter_themePass() {
        var r = service.record(TODAY, "BULL_TREND",
                List.of(input("2454", "ENTER", new BigDecimal("8.0"),
                              trace(Result.PASS, new BigDecimal("8.1")))));
        assertThat(r.counts().get(DecisionDiffType.SAME_BUY)).isEqualTo(1);
        verify(logRepo, times(1)).save(any());
        assertThat(r.entries().get(0).getDecisionDiffType()).isEqualTo("SAME_BUY");
    }

    @Test
    void classify_legacyBuyThemeBlock_legacyEnter_themeBlock() {
        var r = service.record(TODAY, "BULL_TREND",
                List.of(input("2454", "ENTER", new BigDecimal("8.0"),
                              traceWithBlockGate("G2_THEME_VETO", "THEME_STRENGTH_BELOW_MIN"))));
        assertThat(r.counts().get(DecisionDiffType.LEGACY_BUY_THEME_BLOCK)).isEqualTo(1);
        assertThat(r.entries().get(0).getThemeVetoReason())
                .startsWith("G2_THEME_VETO:THEME_STRENGTH_BELOW_MIN");
    }

    @Test
    void classify_legacyWaitThemeBuy_legacyWait_themePass() {
        var r = service.record(TODAY, null,
                List.of(input("2454", "WAIT", new BigDecimal("6.0"),
                              trace(Result.PASS, new BigDecimal("8.2")))));
        assertThat(r.counts().get(DecisionDiffType.LEGACY_WAIT_THEME_BUY)).isEqualTo(1);
    }

    @Test
    void classify_bothBlock_legacyWait_themeBlock() {
        var r = service.record(TODAY, null,
                List.of(input("2454", "REST", new BigDecimal("4.0"),
                              traceWithBlockGate("G6_RR", "RR_BELOW_MIN"))));
        assertThat(r.counts().get(DecisionDiffType.BOTH_BLOCK)).isEqualTo(1);
    }

    @Test
    void classify_sameWait_legacyWait_themeWait() {
        var r = service.record(TODAY, null,
                List.of(input("2454", "WAIT", new BigDecimal("5.0"),
                              trace(Result.WAIT, null))));
        assertThat(r.counts().get(DecisionDiffType.SAME_WAIT)).isEqualTo(1);
    }

    @Test
    void classify_conflictReviewRequired_legacyEnter_themeWait() {
        var r = service.record(TODAY, null,
                List.of(input("2454", "ENTER", new BigDecimal("7.8"),
                              trace(Result.WAIT, null))));
        assertThat(r.counts().get(DecisionDiffType.CONFLICT_REVIEW_REQUIRED)).isEqualTo(1);
    }

    // ── 行為細節 ─────────────────────────────────────────────────────

    @Test
    void scoreDiff_isThemeMinusLegacy() {
        var r = service.record(TODAY, null,
                List.of(input("2454", "ENTER", new BigDecimal("8.0"),
                              trace(Result.PASS, new BigDecimal("8.5")))));
        assertThat(r.entries().get(0).getScoreDiff())
                .isEqualByComparingTo(new BigDecimal("0.5"));
    }

    @Test
    void nullTrace_isSkipped() {
        var r = service.record(TODAY, null,
                List.of(new ThemeShadowModeService.Input("2454", "ENTER",
                        new BigDecimal("8.0"), null, null)));
        assertThat(r.totalRecorded()).isZero();
        verify(logRepo, never()).save(any());
    }

    @Test
    void existingRecord_isUpdatedNotDuplicated() {
        ThemeShadowDecisionLogEntity existing = new ThemeShadowDecisionLogEntity();
        existing.setTradingDate(TODAY);
        existing.setSymbol("2454");
        existing.setLegacyDecision("ENTER");
        existing.setThemeDecision("PASS");
        existing.setDecisionDiffType("SAME_BUY");
        when(logRepo.findByTradingDateAndSymbol(TODAY, "2454")).thenReturn(Optional.of(existing));

        var r = service.record(TODAY, "BULL_TREND",
                List.of(input("2454", "WAIT", new BigDecimal("6.0"),
                              traceWithBlockGate("G4_LIQUIDITY", "LIQUIDITY_BELOW_MIN"))));

        // 同一 entity reference 被覆寫成 BOTH_BLOCK（非 SAME_BUY）
        assertThat(r.entries()).hasSize(1);
        assertThat(r.entries().get(0)).isSameAs(existing);
        assertThat(existing.getDecisionDiffType()).isEqualTo("BOTH_BLOCK");
        assertThat(existing.getThemeDecision()).isEqualTo("BLOCK");
    }

    @Test
    void vetoReason_truncatedTo80() {
        String longReason = "X".repeat(120);
        var r = service.record(TODAY, null,
                List.of(input("2454", "ENTER", new BigDecimal("8.0"),
                              traceWithBlockGate("G2_THEME_VETO", longReason))));
        assertThat(r.entries().get(0).getThemeVetoReason()).hasSize(80);
    }

    // ══════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════

    private static ThemeShadowModeService.Input input(
            String symbol, String legacyDecision, BigDecimal legacyScore, ThemeGateTraceResultDto trace) {
        return new ThemeShadowModeService.Input(symbol, legacyDecision, legacyScore, trace, null);
    }

    private static ThemeGateTraceResultDto trace(Result outcome, BigDecimal themeFinalScore) {
        Result gateResult = outcome == Result.BLOCK ? Result.BLOCK
                : (outcome == Result.WAIT ? Result.WAIT : Result.PASS);
        GateTraceRecordDto g = new GateTraceRecordDto(
                "G1_MARKET_REGIME", "Market", gateResult,
                outcome == Result.PASS ? "OK" : "SAMPLE_REASON",
                "sample", Map.of());
        return new ThemeGateTraceResultDto(
                "2454", List.of(g), outcome,
                new BigDecimal("0.9"), themeFinalScore, new BigDecimal("1.00"),
                "summary", Map.of());
    }

    private static ThemeGateTraceResultDto traceWithBlockGate(String gateKey, String reason) {
        GateTraceRecordDto g = new GateTraceRecordDto(
                gateKey, gateKey, Result.BLOCK, reason, "blocked", Map.of());
        return new ThemeGateTraceResultDto(
                "2454", List.of(g), Result.BLOCK,
                null, null, null, "blocked", Map.of());
    }
}
