package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import com.austin.trading.dto.internal.GateTraceRecordDto;
import com.austin.trading.dto.internal.GateTraceRecordDto.Result;
import com.austin.trading.dto.internal.ThemeGateTraceResultDto;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v2 Theme Engine PR6：Phase 3 live decision override 測試。
 */
class ThemeLiveDecisionServiceTests {

    private ThemeSnapshotProperties props;
    private ThemeLiveDecisionService service;
    private ThemeGateOrchestrator.Outcome outcome;

    @BeforeEach
    void setUp() {
        props = mock(ThemeSnapshotProperties.class);
        when(props.liveDecisionEnabled()).thenReturn(true);
        when(props.liveDecisionWaitOverrideEnabled()).thenReturn(false);
        service = new ThemeLiveDecisionService(props);
    }

    // ── flag / safety ─────────────────────────────────────────────────

    @Test
    void flagDisabled_passthrough() {
        when(props.liveDecisionEnabled()).thenReturn(false);
        outcome = outcomeWith(Map.of("2454", Result.BLOCK));

        ThemeLiveDecisionService.Result r = service.apply("ENTER", mergedOf("2454"), outcome);
        assertThat(r.changed()).isFalse();
        assertThat(r.finalDecisionCode()).isEqualTo("ENTER");
        assertThat(r.merged()).hasSize(1);
    }

    @Test
    void inactiveOutcome_passthrough() {
        outcome = ThemeGateOrchestrator.Outcome.disabled();
        ThemeLiveDecisionService.Result r = service.apply("ENTER", mergedOf("2454"), outcome);
        assertThat(r.changed()).isFalse();
    }

    @Test
    void emptyMerged_passthrough() {
        outcome = outcomeWith(Map.of("2454", Result.BLOCK));
        ThemeLiveDecisionService.Result r = service.apply("REST", List.of(), outcome);
        assertThat(r.changed()).isFalse();
    }

    // ── BLOCK 行為 ────────────────────────────────────────────────────

    @Test
    void block_removesSymbolFromMerged() {
        outcome = outcomeWith(Map.of(
                "2454", Result.BLOCK,
                "2330", Result.PASS));

        ThemeLiveDecisionService.Result r = service.apply("ENTER", mergedOf("2454", "2330"), outcome);
        assertThat(r.changed()).isTrue();
        assertThat(r.merged()).extracting(FinalDecisionSelectedStockResponse::stockCode)
                .containsExactly("2330");
        assertThat(r.removedSymbols()).hasSize(1);
        Map<String, Object> removed = r.removedSymbols().get(0);
        assertThat(removed.get("symbol")).isEqualTo("2454");
        assertThat(removed.get("reasonCode")).isEqualTo("THEME_BLOCK");
        // P1 修正：removedSymbols 必須帶 theme context + vetoReason（來自 G2/G3 payload）
        assertThat(removed.get("themeTag")).isEqualTo("AI_SERVER");
        assertThat(removed.get("themeStrength")).isEqualTo(new BigDecimal("5.0"));
        assertThat(removed.get("vetoReason").toString())
                .startsWith("G2_THEME_VETO:THEME_STRENGTH_BELOW_MIN");
        assertThat(r.finalDecisionCode()).isEqualTo("ENTER");  // 還有 2330 剩下
    }

    @Test
    void block_allRemoved_downgradesEnterToRest() {
        outcome = outcomeWith(Map.of(
                "2454", Result.BLOCK,
                "2330", Result.BLOCK));

        ThemeLiveDecisionService.Result r = service.apply("ENTER", mergedOf("2454", "2330"), outcome);
        assertThat(r.changed()).isTrue();
        assertThat(r.merged()).isEmpty();
        assertThat(r.finalDecisionCode()).isEqualTo("REST");
        assertThat(r.trace()).containsEntry("downgraded", true);
        assertThat(r.trace()).containsEntry("legacyFinalDecisionCode", "ENTER");
        assertThat(r.trace()).containsEntry("newFinalDecisionCode", "REST");
    }

    // ── WAIT 行為（wait_override 預設關）────────────────────────────

    @Test
    void wait_withoutWaitOverride_keepsSymbol() {
        outcome = outcomeWith(Map.of("2454", Result.WAIT));
        ThemeLiveDecisionService.Result r = service.apply("ENTER", mergedOf("2454"), outcome);
        assertThat(r.changed()).isFalse();
        assertThat(r.merged()).hasSize(1);
    }

    @Test
    void wait_withWaitOverrideEnabled_removesSymbol() {
        when(props.liveDecisionWaitOverrideEnabled()).thenReturn(true);
        outcome = outcomeWith(Map.of("2454", Result.WAIT));

        ThemeLiveDecisionService.Result r = service.apply("ENTER", mergedOf("2454"), outcome);
        assertThat(r.changed()).isTrue();
        assertThat(r.merged()).isEmpty();
        Map<String, Object> removed = r.removedSymbols().get(0);
        assertThat(removed.get("reasonCode")).isEqualTo("THEME_WAIT_OVERRIDE");
        // WAIT path 應擷取到 G3 的 rotation / stage / crowding
        assertThat(removed.get("themeTag")).isEqualTo("AI_SERVER");
        assertThat(removed.get("rotationSignal")).isEqualTo("IN");
        assertThat(removed.get("trendStage")).isEqualTo("MID");
        assertThat(removed.get("crowdingRisk")).isEqualTo("LOW");
        assertThat(r.finalDecisionCode()).isEqualTo("REST");
    }

    // ── 其他 ───────────────────────────────────────────────────────────

    @Test
    void pass_keepsSymbol_noChange() {
        outcome = outcomeWith(Map.of("2454", Result.PASS));
        ThemeLiveDecisionService.Result r = service.apply("ENTER", mergedOf("2454"), outcome);
        assertThat(r.changed()).isFalse();
    }

    @Test
    void missingTrace_keepsSymbol() {
        outcome = outcomeWith(Map.of()); // 2454 沒有對應 trace
        ThemeLiveDecisionService.Result r = service.apply("ENTER", mergedOf("2454"), outcome);
        assertThat(r.changed()).isFalse();
    }

    @Test
    void trace_containsLegacyOriginals() {
        outcome = outcomeWith(Map.of("2454", Result.BLOCK));
        ThemeLiveDecisionService.Result r = service.apply("ENTER", mergedOf("2454"), outcome);
        assertThat(r.legacyFinalDecisionCode()).isEqualTo("ENTER");
        assertThat(r.legacyMerged()).extracting(FinalDecisionSelectedStockResponse::stockCode)
                .containsExactly("2454");
        assertThat(r.trace()).containsKey("removedSymbols");
    }

    // ══════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════

    private static List<FinalDecisionSelectedStockResponse> mergedOf(String... symbols) {
        java.util.List<FinalDecisionSelectedStockResponse> list = new java.util.ArrayList<>();
        for (String s : symbols) {
            list.add(new FinalDecisionSelectedStockResponse(
                    s, s + "_name", "BREAKOUT", "zone",
                    100.0, 110.0, 120.0, 2.5,
                    "rationale", 50000.0, 1.0, "SETUP", null));
        }
        return list;
    }

    private static ThemeGateOrchestrator.Outcome outcomeWith(Map<String, Result> symbolToOutcome) {
        java.util.List<ThemeGateTraceResultDto> results = new java.util.ArrayList<>();
        symbolToOutcome.forEach((sym, outcome) -> {
            java.util.List<GateTraceRecordDto> gates = new java.util.ArrayList<>();
            java.util.Map<String, Object> g2Payload = new java.util.LinkedHashMap<>();
            g2Payload.put("themeTag", "AI_SERVER");
            g2Payload.put("themeStrength", new BigDecimal("5.0"));
            g2Payload.put("strengthMin", new BigDecimal("7.0"));
            java.util.Map<String, Object> g3Payload = new java.util.LinkedHashMap<>();
            g3Payload.put("themeTag", "AI_SERVER");
            g3Payload.put("rotationSignal", "IN");
            g3Payload.put("trendStage", "MID");
            g3Payload.put("crowdingRisk", "LOW");
            if (outcome == Result.BLOCK) {
                gates.add(new GateTraceRecordDto("G2_THEME_VETO", "Theme Veto", Result.BLOCK,
                        "THEME_STRENGTH_BELOW_MIN", "題材不足", g2Payload));
                gates.add(new GateTraceRecordDto("G3_THEME_ROTATION", "Theme Rotation", Result.SKIPPED,
                        "SHORT_CIRCUITED_AFTER_BLOCK", "skipped", Map.of()));
            } else if (outcome == Result.WAIT) {
                gates.add(new GateTraceRecordDto("G2_THEME_VETO", "Theme Veto", Result.PASS,
                        "OK", "ok", g2Payload));
                gates.add(new GateTraceRecordDto("G3_THEME_ROTATION", "Theme Rotation", Result.WAIT,
                        "ROTATION_UNKNOWN", "輪動不明", g3Payload));
            } else {
                gates.add(new GateTraceRecordDto("G2_THEME_VETO", "Theme Veto", Result.PASS,
                        "OK", "ok", g2Payload));
                gates.add(new GateTraceRecordDto("G3_THEME_ROTATION", "Theme Rotation", Result.PASS,
                        "OK", "ok", g3Payload));
            }
            results.add(new ThemeGateTraceResultDto(
                    sym, gates, outcome,
                    new BigDecimal("0.9"), new BigDecimal("8.0"), new BigDecimal("1.00"),
                    "summary", Map.of()));
        });
        return new ThemeGateOrchestrator.Outcome(
                true, results, "FRESH", "THEME_ENGINE_OK",
                "DISABLED", "THEME_ENGINE_DISABLED",
                List.of(), List.of(), Map.of());
    }
}
