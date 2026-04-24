package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import com.austin.trading.domain.enums.DecisionDiffType;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v2 Theme Engine PR6：LINE 摘要 formatter 測試。
 */
class ThemeLineSummaryServiceTests {

    private ThemeSnapshotProperties props;
    private ThemeLineSummaryService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 4, 24);

    @BeforeEach
    void setUp() {
        props = mock(ThemeSnapshotProperties.class);
        when(props.lineSummaryEnabled()).thenReturn(true);
        service = new ThemeLineSummaryService(props);
    }

    @Test
    void flagDisabled_returnsEmpty() {
        when(props.lineSummaryEnabled()).thenReturn(false);
        Optional<String> out = service.formatDailySummary(TODAY, shadowReport(3), null);
        assertThat(out).isEmpty();
    }

    @Test
    void bothNull_returnsEmpty() {
        Optional<String> out = service.formatDailySummary(TODAY, null, null);
        assertThat(out).isEmpty();
    }

    @Test
    void emptyShadow_andNoOverride_returnsEmpty() {
        Optional<String> out = service.formatDailySummary(TODAY, shadowReport(0), null);
        assertThat(out).isEmpty();
    }

    @Test
    void shadowOnly_formatsCountsAndStats() {
        Optional<String> out = service.formatDailySummary(TODAY, shadowReport(5), null);
        assertThat(out).isPresent();
        String text = out.get();
        assertThat(text)
                .contains("Theme Engine 摘要 — 2026-04-24")
                .contains("Shadow Diff（total=5）")
                .contains("同買：")
                .contains("L買T擋：")
                .contains("需檢視：")
                .contains("avgDiff:")
                .contains("p90|Δ|:")
                .contains("來源：Java Theme Engine v2");
    }

    @Test
    void overrideOnly_formatsRemovedSymbols() {
        Optional<String> out = service.formatDailySummary(TODAY, null, overrideResult(List.of("2454", "2330"), "ENTER", "REST"));
        assertThat(out).isPresent();
        String text = out.get();
        assertThat(text)
                .contains("Live Override")
                .contains("ENTER → REST")
                .contains("removed 2")
                .contains("2454")
                .contains("2330")
                // P1 修正：LINE 必須呈現 themeTag / strength / stage / rotation / crowding / vetoReason
                .contains("AI_SERVER")
                .contains("S=5.0")
                .contains("MID")
                .contains("rot=IN")
                .contains("crowd=LOW")
                .contains("G2_THEME_VETO:THEME_STRENGTH_BELOW_MIN");
    }

    @Test
    void shadowAndOverride_bothSections() {
        Optional<String> out = service.formatDailySummary(TODAY, shadowReport(5),
                overrideResult(List.of("2454"), "ENTER", "ENTER"));
        String text = out.orElseThrow();
        assertThat(text).contains("Shadow Diff").contains("Live Override");
    }

    @Test
    void overrideRemovedSymbols_cappedAt5Shown() {
        List<String> many = List.of("1", "2", "3", "4", "5", "6", "7");
        Optional<String> out = service.formatDailySummary(TODAY, null,
                overrideResult(many, "ENTER", "REST"));
        String text = out.orElseThrow();
        assertThat(text).contains("還有 2 筆");
    }

    // ══════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════

    private static ThemeShadowReportService.ReportResult shadowReport(int total) {
        Map<DecisionDiffType, Integer> counts = new EnumMap<>(DecisionDiffType.class);
        for (DecisionDiffType t : DecisionDiffType.values()) counts.put(t, 0);
        if (total > 0) {
            counts.put(DecisionDiffType.SAME_BUY, total - 1);
            counts.put(DecisionDiffType.LEGACY_BUY_THEME_BLOCK, 1);
        }
        return new ThemeShadowReportService.ReportResult(
                true, TODAY, total, counts,
                new BigDecimal("0.250"), new BigDecimal("1.500"),
                null, null, null, null);
    }

    private static ThemeLiveDecisionService.Result overrideResult(
            List<String> removedSymbols, String legacyCode, String newCode) {
        List<Map<String, Object>> removed = new ArrayList<>();
        for (String s : removedSymbols) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("symbol", s);
            m.put("reasonCode", "THEME_BLOCK");
            m.put("detail", "G2_THEME_VETO:THEME_STRENGTH_BELOW_MIN");
            m.put("vetoReason", "G2_THEME_VETO:THEME_STRENGTH_BELOW_MIN");
            m.put("themeTag", "AI_SERVER");
            m.put("themeStrength", new BigDecimal("5.0"));
            m.put("trendStage", "MID");
            m.put("rotationSignal", "IN");
            m.put("crowdingRisk", "LOW");
            removed.add(m);
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("liveDecisionEnabled", true);
        trace.put("legacyFinalDecisionCode", legacyCode);
        trace.put("newFinalDecisionCode", newCode);
        return new ThemeLiveDecisionService.Result(
                true, newCode, List.<FinalDecisionSelectedStockResponse>of(),
                legacyCode, List.<FinalDecisionSelectedStockResponse>of(),
                removed, trace);
    }
}
