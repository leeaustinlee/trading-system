package com.austin.trading.engine;

import com.austin.trading.dto.internal.PortfolioRiskDecision;
import com.austin.trading.dto.internal.PortfolioRiskInput;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.service.ScoreConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-unit tests for {@link PortfolioRiskEngine}: no DB, ScoreConfigService mocked.
 */
class PortfolioRiskEngineTests {

    private PortfolioRiskEngine engine;
    private static final LocalDate DATE = LocalDate.of(2026, 4, 21);

    @BeforeEach
    void setUp() {
        ScoreConfigService config = mock(ScoreConfigService.class);
        when(config.getDecimal(anyString(), any(BigDecimal.class))).thenAnswer(inv -> inv.getArgument(1));
        when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(config.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        engine = new PortfolioRiskEngine(config, new ObjectMapper());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private RankedCandidate candidate(String symbol, String theme) {
        return new RankedCandidate(DATE, symbol,
                new BigDecimal("8.0"), new BigDecimal("7.5"), new BigDecimal("7.0"),
                new BigDecimal("7.8"), theme,
                false, true, null, "{}");
    }

    private PositionEntity openPosition(String symbol, String reviewStatus) {
        PositionEntity p = new PositionEntity();
        p.setSymbol(symbol);
        p.setStatus("OPEN");
        p.setReviewStatus(reviewStatus);
        p.setQty(new BigDecimal("1000"));
        p.setAvgCost(new BigDecimal("100.0"));
        return p;
    }

    // ── Portfolio gate ─────────────────────────────────────────────────────

    @Test
    void gate_belowMax_approved() {
        List<PositionEntity> positions = List.of(openPosition("AAA", "HOLD"));
        PortfolioRiskInput in = new PortfolioRiskInput(
                null, positions, 3, false, Map.of(), BigDecimal.ZERO);

        PortfolioRiskDecision d = engine.evaluatePortfolioGate(in, DATE);
        assertThat(d.approved()).isTrue();
        assertThat(d.symbol()).isNull();
    }

    @Test
    void gate_atMax_blocked() {
        List<PositionEntity> positions = List.of(
                openPosition("AAA", "HOLD"),
                openPosition("BBB", "WEAK"),
                openPosition("CCC", "HOLD")
        );
        PortfolioRiskInput in = new PortfolioRiskInput(
                null, positions, 3, false, Map.of(), BigDecimal.ZERO);

        PortfolioRiskDecision d = engine.evaluatePortfolioGate(in, DATE);
        assertThat(d.approved()).isFalse();
        assertThat(d.blockReason()).isEqualTo(PortfolioRiskEngine.BLOCK_PORTFOLIO_FULL);
    }

    @Test
    void gate_fullButAllStrongAndAllowEnabled_approved() {
        List<PositionEntity> positions = List.of(
                openPosition("AAA", "STRONG"),
                openPosition("BBB", "STRONG"),
                openPosition("CCC", "STRONG")
        );
        PortfolioRiskInput in = new PortfolioRiskInput(
                null, positions, 3, true, Map.of(), BigDecimal.ZERO);

        PortfolioRiskDecision d = engine.evaluatePortfolioGate(in, DATE);
        assertThat(d.approved()).isTrue();
    }

    @Test
    void gate_fullMixedStatusAndAllowEnabled_blocked() {
        // One WEAK position → not all STRONG → gate blocks
        List<PositionEntity> positions = List.of(
                openPosition("AAA", "STRONG"),
                openPosition("BBB", "WEAK"),
                openPosition("CCC", "STRONG")
        );
        PortfolioRiskInput in = new PortfolioRiskInput(
                null, positions, 3, true, Map.of(), BigDecimal.ZERO);

        PortfolioRiskDecision d = engine.evaluatePortfolioGate(in, DATE);
        assertThat(d.approved()).isFalse();
        assertThat(d.blockReason()).isEqualTo(PortfolioRiskEngine.BLOCK_PORTFOLIO_FULL);
    }

    // ── Per-candidate: already-held ────────────────────────────────────────

    @Test
    void candidate_alreadyHeld_blocked() {
        List<PositionEntity> positions = List.of(openPosition("2330", "HOLD"));
        PortfolioRiskInput in = new PortfolioRiskInput(
                candidate("2330", "AI_THEME"),
                positions, 3, false,
                Map.of(), new BigDecimal("60.0"));

        PortfolioRiskDecision d = engine.evaluateCandidate(in, DATE);
        assertThat(d.approved()).isFalse();
        assertThat(d.blockReason()).isEqualTo(PortfolioRiskEngine.BLOCK_ALREADY_HELD);
    }

    // ── Per-candidate: theme over-exposure ────────────────────────────────

    @Test
    void candidate_themeOverExposed_blocked() {
        // Theme "AI_THEME" already at 65% > max 60%
        Map<String, BigDecimal> exposure = Map.of("AI_THEME", new BigDecimal("65.0"));
        PortfolioRiskInput in = new PortfolioRiskInput(
                candidate("2382", "AI_THEME"),
                List.of(openPosition("2330", "STRONG")),
                3, false,
                exposure, new BigDecimal("60.0"));

        PortfolioRiskDecision d = engine.evaluateCandidate(in, DATE);
        assertThat(d.approved()).isFalse();
        assertThat(d.blockReason()).isEqualTo(PortfolioRiskEngine.BLOCK_THEME_OVER_EXPOSED);
        assertThat(d.themeExposurePct()).isEqualByComparingTo("65.0");
    }

    @Test
    void candidate_themeJustBelowLimit_approved() {
        Map<String, BigDecimal> exposure = Map.of("AI_THEME", new BigDecimal("59.9"));
        PortfolioRiskInput in = new PortfolioRiskInput(
                candidate("2382", "AI_THEME"),
                List.of(openPosition("2330", "STRONG")),
                3, false,
                exposure, new BigDecimal("60.0"));

        PortfolioRiskDecision d = engine.evaluateCandidate(in, DATE);
        assertThat(d.approved()).isTrue();
    }

    @Test
    void candidate_differentTheme_approved() {
        // Portfolio 65% in "EV_THEME", candidate is in "AI_THEME" → ok
        Map<String, BigDecimal> exposure = Map.of("EV_THEME", new BigDecimal("65.0"));
        PortfolioRiskInput in = new PortfolioRiskInput(
                candidate("2382", "AI_THEME"),
                List.of(openPosition("EV001", "HOLD")),
                3, false,
                exposure, new BigDecimal("60.0"));

        PortfolioRiskDecision d = engine.evaluateCandidate(in, DATE);
        assertThat(d.approved()).isTrue();
    }

    @Test
    void candidate_noTheme_approved() {
        // Candidate has null theme → theme check skipped
        PortfolioRiskInput in = new PortfolioRiskInput(
                candidate("2382", null),
                List.of(), 3, false,
                Map.of(), new BigDecimal("60.0"));

        PortfolioRiskDecision d = engine.evaluateCandidate(in, DATE);
        assertThat(d.approved()).isTrue();
    }

    // ── Batch evaluate ─────────────────────────────────────────────────────

    @Test
    void evaluateCandidates_batch_correctCount() {
        Map<String, BigDecimal> exposure = Map.of("AI_THEME", new BigDecimal("70.0")); // over limit
        List<PortfolioRiskInput> inputs = List.of(
                new PortfolioRiskInput(candidate("A", "AI_THEME"), List.of(), 3, false,
                        exposure, new BigDecimal("60.0")),
                new PortfolioRiskInput(candidate("B", "EV_THEME"), List.of(), 3, false,
                        exposure, new BigDecimal("60.0"))
        );

        List<PortfolioRiskDecision> results = engine.evaluateCandidates(inputs, DATE);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).approved()).isFalse();  // AI_THEME over-exposed
        assertThat(results.get(1).approved()).isTrue();   // EV_THEME fine
    }

    // ── Payload ────────────────────────────────────────────────────────────

    @Test
    void payloadJson_isParseable() {
        PortfolioRiskInput in = new PortfolioRiskInput(
                candidate("2330", "AI_THEME"), List.of(), 3, false,
                Map.of("AI_THEME", new BigDecimal("30.0")), new BigDecimal("60.0"));

        PortfolioRiskDecision d = engine.evaluateCandidate(in, DATE);
        assertThat(d.payloadJson()).isNotNull().startsWith("{").contains("approved");
    }
}
