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
import org.mockito.ArgumentCaptor;

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
 * P0.2 修正後 ThemeShadowModeService 測試：write-on-every-comparison + 新 5 類 diffType。
 *
 * <p>過去測試 30 天累計 0 row 是因為 runtime flag/gate 條件未對齊；
 * 本次修正把所有比對都落 log，並用下列 5 類取代舊 6 類：</p>
 * <ul>
 *   <li>{@link DecisionDiffType#SAME_DECISION_SAME_SCORE}</li>
 *   <li>{@link DecisionDiffType#SAME_DECISION_SCORE_DIFF}</li>
 *   <li>{@link DecisionDiffType#DIFF_DECISION}</li>
 *   <li>{@link DecisionDiffType#LEGACY_VETO_V2_PASS}</li>
 *   <li>{@link DecisionDiffType#V2_VETO_LEGACY_PASS}</li>
 * </ul>
 */
class ThemeShadowModeServiceTests {

    private ThemeSnapshotProperties props;
    private ThemeShadowDecisionLogRepository logRepo;
    private ThemeShadowModeService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 27);

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
    void flagDisabled_skipsAllWork_noRowWritten() {
        when(props.shadowModeEnabled()).thenReturn(false);
        var result = service.record(TODAY, "BULL_TREND",
                List.of(input("2454", "ENTER", new BigDecimal("8.0"),
                              trace(Result.PASS, new BigDecimal("8.05")))));
        assertThat(result.active()).isFalse();
        verifyNoInteractions(logRepo);
    }

    @Test
    void emptyInputs_returnsEmptyResult_noRowWritten() {
        var result = service.record(TODAY, "BULL_TREND", List.of());
        assertThat(result.totalRecorded()).isZero();
        verifyNoInteractions(logRepo);
    }

    // ── P0.2 五類 diffType ────────────────────────────────────────────

    /** Enabled + 同決策（legacy ENTER, theme PASS）+ scoreDiff <= 0.1 → SAME_DECISION_SAME_SCORE */
    @Test
    void classify_sameDecisionSameScore_legacyEnterThemePass_smallDiff() {
        ArgumentCaptor<ThemeShadowDecisionLogEntity> captor =
                ArgumentCaptor.forClass(ThemeShadowDecisionLogEntity.class);

        var r = service.record(TODAY, "BULL_TREND",
                List.of(input("2454", "ENTER", new BigDecimal("8.00"),
                              trace(Result.PASS, new BigDecimal("8.05")))));

        verify(logRepo, times(1)).save(captor.capture());
        assertThat(r.totalRecorded()).isEqualTo(1);
        assertThat(r.counts().get(DecisionDiffType.SAME_DECISION_SAME_SCORE)).isEqualTo(1);
        ThemeShadowDecisionLogEntity row = captor.getValue();
        assertThat(row.getDecisionDiffType()).isEqualTo("SAME_DECISION_SAME_SCORE");
        assertThat(row.getLegacyDecision()).isEqualTo("ENTER");
        assertThat(row.getThemeDecision()).isEqualTo("PASS");
    }

    /** Enabled + 同決策（兩邊 WAIT/BLOCK 視為 not-enter）+ scoreDiff <= 0.1 */
    @Test
    void classify_sameDecisionSameScore_legacyWaitThemeWait() {
        var r = service.record(TODAY, null,
                List.of(input("2454", "WAIT", new BigDecimal("5.00"),
                              trace(Result.WAIT, new BigDecimal("5.05")))));

        verify(logRepo, times(1)).save(any());
        assertThat(r.counts().get(DecisionDiffType.SAME_DECISION_SAME_SCORE)).isEqualTo(1);
    }

    /** Enabled + 同決策 + scoreDiff > 0.1 → SAME_DECISION_SCORE_DIFF */
    @Test
    void classify_sameDecisionScoreDiff_legacyEnterThemePass_largeDiff() {
        ArgumentCaptor<ThemeShadowDecisionLogEntity> captor =
                ArgumentCaptor.forClass(ThemeShadowDecisionLogEntity.class);

        var r = service.record(TODAY, "BULL_TREND",
                List.of(input("2454", "ENTER", new BigDecimal("8.00"),
                              trace(Result.PASS, new BigDecimal("8.50")))));

        verify(logRepo, times(1)).save(captor.capture());
        assertThat(r.counts().get(DecisionDiffType.SAME_DECISION_SCORE_DIFF)).isEqualTo(1);
        ThemeShadowDecisionLogEntity row = captor.getValue();
        assertThat(row.getDecisionDiffType()).isEqualTo("SAME_DECISION_SCORE_DIFF");
        assertThat(row.getScoreDiff()).isEqualByComparingTo(new BigDecimal("0.50"));
    }

    /** Enabled + 兩邊 not-enter 但 scoreDiff > 0.1 → SAME_DECISION_SCORE_DIFF */
    @Test
    void classify_sameDecisionScoreDiff_legacyWaitThemeWait_largeDiff() {
        var r = service.record(TODAY, null,
                List.of(input("2454", "WAIT", new BigDecimal("4.00"),
                              trace(Result.WAIT, new BigDecimal("5.50")))));

        verify(logRepo, times(1)).save(any());
        assertThat(r.counts().get(DecisionDiffType.SAME_DECISION_SCORE_DIFF)).isEqualTo(1);
    }

    /** Enabled + legacy ENTER + theme WAIT → DIFF_DECISION（無單邊 veto） */
    @Test
    void classify_diffDecision_legacyEnterThemeWait() {
        ArgumentCaptor<ThemeShadowDecisionLogEntity> captor =
                ArgumentCaptor.forClass(ThemeShadowDecisionLogEntity.class);

        var r = service.record(TODAY, null,
                List.of(input("2454", "ENTER", new BigDecimal("7.80"),
                              trace(Result.WAIT, new BigDecimal("6.20")))));

        verify(logRepo, times(1)).save(captor.capture());
        assertThat(r.counts().get(DecisionDiffType.DIFF_DECISION)).isEqualTo(1);
        assertThat(captor.getValue().getDecisionDiffType()).isEqualTo("DIFF_DECISION");
    }

    /** Enabled + legacy ENTER + theme BLOCK → V2_VETO_LEGACY_PASS（恰 v2 單邊 veto） */
    @Test
    void classify_v2VetoLegacyPass_legacyEnterThemeBlock() {
        var r = service.record(TODAY, "BULL_TREND",
                List.of(input("2454", "ENTER", new BigDecimal("8.00"),
                              traceWithBlockGate("G2_THEME_VETO", "THEME_STRENGTH_BELOW_MIN"))));

        verify(logRepo, times(1)).save(any());
        assertThat(r.counts().get(DecisionDiffType.V2_VETO_LEGACY_PASS)).isEqualTo(1);
        assertThat(r.entries().get(0).getThemeVetoReason())
                .startsWith("G2_THEME_VETO:THEME_STRENGTH_BELOW_MIN");
    }

    /** Enabled + legacy WAIT + theme PASS → LEGACY_VETO_V2_PASS（恰 legacy 單邊 veto） */
    @Test
    void classify_legacyVetoV2Pass_legacyWaitThemePass() {
        var r = service.record(TODAY, null,
                List.of(input("2454", "WAIT", new BigDecimal("6.00"),
                              trace(Result.PASS, new BigDecimal("8.20")))));

        verify(logRepo, times(1)).save(any());
        assertThat(r.counts().get(DecisionDiffType.LEGACY_VETO_V2_PASS)).isEqualTo(1);
    }

    // ── 行為細節 ─────────────────────────────────────────────────────

    /** 多筆混合輸入：每筆都應落 log（不再因為「相同」就跳過寫入） */
    @Test
    void writesOneRowPerComparison_evenWhenMostAreSame() {
        var r = service.record(TODAY, "BULL_TREND", List.of(
                input("2454", "ENTER", new BigDecimal("8.00"),
                      trace(Result.PASS, new BigDecimal("8.02"))),  // SAME_SAME
                input("2330", "ENTER", new BigDecimal("9.00"),
                      trace(Result.PASS, new BigDecimal("9.50"))),  // SAME_DIFF
                input("3017", "ENTER", new BigDecimal("7.50"),
                      traceWithBlockGate("G6_RR", "RR_BELOW_MIN")), // V2_VETO
                input("2317", "WAIT",  new BigDecimal("6.00"),
                      trace(Result.PASS, new BigDecimal("8.10")))   // LEGACY_VETO
        ));

        // 所有 4 筆都應落 log
        verify(logRepo, times(4)).save(any());
        assertThat(r.totalRecorded()).isEqualTo(4);
        assertThat(r.counts().get(DecisionDiffType.SAME_DECISION_SAME_SCORE)).isEqualTo(1);
        assertThat(r.counts().get(DecisionDiffType.SAME_DECISION_SCORE_DIFF)).isEqualTo(1);
        assertThat(r.counts().get(DecisionDiffType.V2_VETO_LEGACY_PASS)).isEqualTo(1);
        assertThat(r.counts().get(DecisionDiffType.LEGACY_VETO_V2_PASS)).isEqualTo(1);
    }

    @Test
    void scoreDiff_isThemeMinusLegacy_andBoundary() {
        // 邊界：差距正好 0.1 應視為 SAME_DECISION_SAME_SCORE
        var r = service.record(TODAY, null,
                List.of(input("2454", "ENTER", new BigDecimal("8.00"),
                              trace(Result.PASS, new BigDecimal("8.10")))));
        assertThat(r.entries().get(0).getScoreDiff())
                .isEqualByComparingTo(new BigDecimal("0.10"));
        assertThat(r.counts().get(DecisionDiffType.SAME_DECISION_SAME_SCORE)).isEqualTo(1);
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
        existing.setDecisionDiffType("SAME_DECISION_SAME_SCORE");
        when(logRepo.findByTradingDateAndSymbol(TODAY, "2454")).thenReturn(Optional.of(existing));

        // 同一檔但 theme 反轉成 BLOCK → V2_VETO_LEGACY_PASS
        var r = service.record(TODAY, "BULL_TREND",
                List.of(input("2454", "ENTER", new BigDecimal("8.0"),
                              traceWithBlockGate("G4_LIQUIDITY", "LIQUIDITY_BELOW_MIN"))));

        assertThat(r.entries()).hasSize(1);
        assertThat(r.entries().get(0)).isSameAs(existing);
        assertThat(existing.getDecisionDiffType()).isEqualTo("V2_VETO_LEGACY_PASS");
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

    @Test
    void nullScoreDiff_treatedAsZero_sameDecisionSameScore() {
        // 兩邊 score 缺一 → scoreDiff=null → 同決策時視為 0 → SAME_DECISION_SAME_SCORE
        var r = service.record(TODAY, null,
                List.of(input("2454", "ENTER", null,
                              trace(Result.PASS, new BigDecimal("8.0")))));
        assertThat(r.counts().get(DecisionDiffType.SAME_DECISION_SAME_SCORE)).isEqualTo(1);
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
