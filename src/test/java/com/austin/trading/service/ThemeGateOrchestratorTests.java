package com.austin.trading.service;

import com.austin.trading.config.ThemeSnapshotProperties;
import com.austin.trading.dto.internal.ClaudeThemeResearchOutputDto;
import com.austin.trading.dto.internal.GateTraceRecordDto.Result;
import com.austin.trading.dto.internal.ThemeGateTraceResultDto;
import com.austin.trading.dto.internal.ThemeSnapshotV2Dto;
import com.austin.trading.engine.ThemeGateTraceEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * v2 Theme Engine PR4：ThemeGateOrchestrator wiring + flag-off 測試。
 */
class ThemeGateOrchestratorTests {

    private ScoreConfigService scoreConfig;
    private ThemeSnapshotProperties props;
    private ThemeSnapshotService snapshotService;
    private ClaudeThemeResearchParserService claudeParser;
    private ThemeContextMergeService mergeService;
    private ThemeGateTraceEngine engine;

    private ThemeGateOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        scoreConfig = mock(ScoreConfigService.class);
        // 大部分 flag / config 都走 default，trace 開關會在個別 test 改
        when(scoreConfig.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        when(scoreConfig.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(scoreConfig.getString(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(scoreConfig.getDecimal(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        props = mock(ThemeSnapshotProperties.class);
        snapshotService = mock(ThemeSnapshotService.class);
        claudeParser = mock(ClaudeThemeResearchParserService.class);
        mergeService = new ThemeContextMergeService();   // 真實 service，無副作用
        engine = new ThemeGateTraceEngine(scoreConfig);

        orchestrator = new ThemeGateOrchestrator(
                props, snapshotService, claudeParser, mergeService, engine);
    }

    @Test
    void flagDisabled_returnsDisabled_noServiceCalls() {
        when(props.gateTraceEnabled()).thenReturn(false);

        ThemeGateOrchestrator.Outcome out = orchestrator.traceCandidates(List.of(sampleProbe()));

        assertThat(out.active()).isFalse();
        assertThat(out.snapshotTraceKey()).isEqualTo("THEME_GATE_TRACE_DISABLED");
        verifyNoInteractions(snapshotService, claudeParser);
    }

    @Test
    void emptyCandidates_returnsEmptyOutcome() {
        when(props.gateTraceEnabled()).thenReturn(true);
        ThemeGateOrchestrator.Outcome out = orchestrator.traceCandidates(List.of());
        assertThat(out.active()).isTrue();
        assertThat(out.results()).isEmpty();
    }

    @Test
    void snapshotDisabled_runsGatesWithNullContext_G2ThemeVetoShouldWait() {
        when(props.gateTraceEnabled()).thenReturn(true);
        when(snapshotService.getCurrentSnapshot()).thenReturn(disabledSnapshot());
        when(claudeParser.loadCurrent()).thenReturn(disabledClaude());

        ThemeGateOrchestrator.Outcome out = orchestrator.traceCandidates(List.of(sampleProbe()));

        assertThat(out.active()).isTrue();
        assertThat(out.results()).hasSize(1);
        ThemeGateTraceResultDto r = out.results().get(0);
        // 無 context → G2 WAIT，整體最壞 WAIT（沒有 BLOCK）
        assertThat(r.findGate("G2_THEME_VETO").result()).isEqualTo(Result.WAIT);
        assertThat(r.findGate("G3_THEME_ROTATION").result()).isEqualTo(Result.WAIT);
        assertThat(r.overallOutcome()).isEqualTo(Result.WAIT);
        verify(snapshotService).getCurrentSnapshot();
        verify(claudeParser).loadCurrent();
    }

    @Test
    void freshSnapshotWithMatchingContext_gatesPass() {
        when(props.gateTraceEnabled()).thenReturn(true);
        ThemeSnapshotV2Dto snapshot = buildFreshSnapshot();
        when(snapshotService.getCurrentSnapshot()).thenReturn(freshWith(snapshot));
        when(claudeParser.loadCurrent()).thenReturn(disabledClaude());

        ThemeGateOrchestrator.Outcome out = orchestrator.traceCandidates(List.of(sampleProbe()));

        assertThat(out.active()).isTrue();
        ThemeGateTraceResultDto r = out.results().get(0);
        assertThat(r.overallOutcome()).isEqualTo(Result.PASS);
        assertThat(r.findGate("G2_THEME_VETO").result()).isEqualTo(Result.PASS);
        assertThat(r.findGate("G3_THEME_ROTATION").result()).isEqualTo(Result.PASS);
        assertThat(out.snapshotStatus()).isEqualTo("FRESH");
        assertThat(out.claudeStatus()).isEqualTo("DISABLED");
    }

    @Test
    void nullThemeTag_fallsBackToSymbolContext_andEmitsWarning() {
        when(props.gateTraceEnabled()).thenReturn(true);
        ThemeSnapshotV2Dto snapshot = buildFreshSnapshot();
        when(snapshotService.getCurrentSnapshot()).thenReturn(freshWith(snapshot));
        when(claudeParser.loadCurrent()).thenReturn(disabledClaude());

        // probe themeTag 為 null → 應 fallback 到 symbol 底下的 AI_SERVER context
        ThemeGateOrchestrator.CandidateProbe probe = new ThemeGateOrchestrator.CandidateProbe(
                "2454", null,
                "BULL_TREND", true, new BigDecimal("1.0"),
                new BigDecimal("500000000"), new BigDecimal("1.2"),
                new BigDecimal("8.0"), new BigDecimal("8.2"), new BigDecimal("8.1"),
                new BigDecimal("2.5"), new BigDecimal("8.5"),
                1, 4, new BigDecimal("100000")
        );

        ThemeGateOrchestrator.Outcome out = orchestrator.traceCandidates(List.of(probe));

        ThemeGateTraceResultDto r = out.results().get(0);
        assertThat(r.findGate("G2_THEME_VETO").result()).isEqualTo(Result.PASS);
        assertThat(r.findGate("G3_THEME_ROTATION").result()).isEqualTo(Result.PASS);
        assertThat(out.mergeWarnings())
                .anyMatch(w -> w.contains("THEME_CONTEXT_FALLBACK_BY_SYMBOL") && w.contains("2454"));
        assertThat(out.summary()).containsEntry("fallbackBySymbol", 1);
    }

    @Test
    void outcome_findBySymbol_works() {
        when(props.gateTraceEnabled()).thenReturn(true);
        ThemeSnapshotV2Dto snapshot = buildFreshSnapshot();
        when(snapshotService.getCurrentSnapshot()).thenReturn(freshWith(snapshot));
        when(claudeParser.loadCurrent()).thenReturn(disabledClaude());

        ThemeGateOrchestrator.Outcome out = orchestrator.traceCandidates(List.of(sampleProbe()));
        Optional<ThemeGateTraceResultDto> found = out.findBySymbol("2454");
        assertThat(found).isPresent();
        assertThat(found.get().symbol()).isEqualTo("2454");
    }

    // ── helpers ─────────────────────────────────────────────────────

    private ThemeGateOrchestrator.CandidateProbe sampleProbe() {
        return new ThemeGateOrchestrator.CandidateProbe(
                "2454", "AI_SERVER",
                "BULL_TREND", true, new BigDecimal("1.0"),
                new BigDecimal("500000000"), new BigDecimal("1.2"),
                new BigDecimal("8.0"), new BigDecimal("8.2"), new BigDecimal("8.1"),
                new BigDecimal("2.5"), new BigDecimal("8.5"),
                1, 4, new BigDecimal("100000")
        );
    }

    private ThemeSnapshotService.LoadResult disabledSnapshot() {
        return new ThemeSnapshotService.LoadResult(
                Optional.empty(),
                ThemeSnapshotService.Status.DISABLED,
                ThemeSnapshotService.TRACE_KEY_DISABLED,
                "disabled", null, null);
    }

    private ThemeSnapshotService.LoadResult freshWith(ThemeSnapshotV2Dto snap) {
        return new ThemeSnapshotService.LoadResult(
                Optional.of(snap),
                ThemeSnapshotService.Status.FRESH,
                ThemeSnapshotService.TRACE_KEY_OK,
                "fresh",
                snap.generatedAt(), Duration.ofMinutes(5));
    }

    private ClaudeThemeResearchParserService.LoadResult disabledClaude() {
        return new ClaudeThemeResearchParserService.LoadResult(
                Optional.empty(),
                ClaudeThemeResearchParserService.Status.DISABLED,
                ClaudeThemeResearchParserService.TRACE_KEY_DISABLED,
                "disabled", null, null);
    }

    private ThemeSnapshotV2Dto buildFreshSnapshot() {
        return new ThemeSnapshotV2Dto(
                OffsetDateTime.now(),
                new ThemeSnapshotV2Dto.MarketRegime("BULL_TREND", new BigDecimal("1.0"), true),
                List.of(new ThemeSnapshotV2Dto.Theme(
                        "AI_SERVER",
                        new BigDecimal("7.5"),
                        "MID", "IN",
                        new BigDecimal("7.0"), new BigDecimal("7.0"),
                        "LOW",
                        List.of("NEWS"),
                        new BigDecimal("0.85"),
                        null,
                        List.of(new ThemeSnapshotV2Dto.ThemeCandidate(
                                "2454", "LEADER", new BigDecimal("0.9")))
                ))
        );
    }
}
