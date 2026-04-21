package com.austin.trading.engine;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.RankCandidateInput;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.service.ScoreConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-unit tests for {@link StockRankingEngine}: no DB, ScoreConfigService mocked
 * to return supplied defaults.
 */
class StockRankingEngineTests {

    private StockRankingEngine engine;
    private static final LocalDate DATE = LocalDate.of(2026, 4, 21);

    @BeforeEach
    void setUp() {
        ScoreConfigService config = mock(ScoreConfigService.class);
        when(config.getDecimal(anyString(), any(BigDecimal.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        when(config.getInt(anyString(), any(Integer.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        engine = new StockRankingEngine(config, new ObjectMapper());
    }

    private MarketRegimeDecision bullRegime() {
        return new MarketRegimeDecision(
                DATE, LocalDateTime.now(), "BULL_TREND", "A",
                true, new BigDecimal("1.0"),
                List.of("BREAKOUT_CONTINUATION", "PULLBACK_CONFIRMATION", "EVENT_SECOND_LEG"),
                "regime=BULL_TREND", "[]", "{}"
        );
    }

    private RankCandidateInput candidate(String symbol, double java_, double claude, double rs, double theme,
                                          boolean vetoed, boolean cooldown, boolean held) {
        return new RankCandidateInput(DATE, symbol,
                new BigDecimal(java_), new BigDecimal(claude), null,
                null,
                new BigDecimal(rs), new BigDecimal(theme),
                "AI_THEME",
                vetoed, cooldown, held);
    }

    @Test
    void hardReject_alreadyHeld() {
        List<RankedCandidate> result = engine.rank(
                List.of(candidate("2330", 8.0, 7.0, 7.0, 6.0, false, false, true)),
                bullRegime());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).vetoed()).isTrue();
        assertThat(result.get(0).eligibleForSetup()).isFalse();
        assertThat(result.get(0).rejectionReason()).isEqualTo("ALREADY_HELD");
    }

    @Test
    void hardReject_inCooldown() {
        List<RankedCandidate> result = engine.rank(
                List.of(candidate("2330", 8.0, 7.0, 7.0, 6.0, false, true, false)),
                bullRegime());

        assertThat(result.get(0).rejectionReason()).isEqualTo("IN_COOLDOWN");
    }

    @Test
    void hardReject_vetoed() {
        List<RankedCandidate> result = engine.rank(
                List.of(candidate("2330", 8.0, 7.0, 7.0, 6.0, true, false, false)),
                bullRegime());

        // Reason is "VETOED" (not "CODEX_VETOED") because P0.2 uses VetoEngine combined result
        assertThat(result.get(0).rejectionReason()).isEqualTo("VETOED");
    }

    @Test
    void eligibleCandidate_computesSelectionScore() {
        // selectionScore = rs*0.30 + theme*0.25 + java*0.25 + thesis*0.20
        // = 8*0.30 + 6*0.25 + 7*0.25 + 7*0.20
        // = 2.40 + 1.50 + 1.75 + 1.40 = 7.05
        RankCandidateInput in = candidate("3008", 7.0, 7.0, 8.0, 6.0, false, false, false);
        List<RankedCandidate> result = engine.rank(List.of(in), bullRegime());

        assertThat(result).hasSize(1);
        RankedCandidate r = result.get(0);
        assertThat(r.vetoed()).isFalse();
        assertThat(r.eligibleForSetup()).isTrue();
        assertThat(r.selectionScore()).isEqualByComparingTo("7.050");
    }

    @Test
    void scoreBelowMin_marksIneligible() {
        // min_selection_score defaults to 4.0; scores all 0.5 → selectionScore = 0.5
        RankCandidateInput in = candidate("1234", 0.5, 0.5, 0.5, 0.5, false, false, false);
        List<RankedCandidate> result = engine.rank(List.of(in), bullRegime());

        assertThat(result.get(0).eligibleForSetup()).isFalse();
        assertThat(result.get(0).rejectionReason()).contains("SCORE_BELOW_MIN");
    }

    @Test
    void topNCap_marksOverflowIneligible() {
        // default topN=3; supply 4 eligible candidates above min score
        List<RankCandidateInput> inputs = List.of(
                candidate("A", 9.0, 8.0, 9.0, 8.0, false, false, false),
                candidate("B", 8.0, 7.0, 8.0, 7.0, false, false, false),
                candidate("C", 7.0, 6.0, 7.0, 6.0, false, false, false),
                candidate("D", 6.0, 5.0, 6.0, 5.0, false, false, false)
        );
        List<RankedCandidate> result = engine.rank(inputs, bullRegime());

        long eligible = result.stream().filter(RankedCandidate::eligibleForSetup).count();
        assertThat(eligible).isEqualTo(3);

        // D should be cut
        RankedCandidate d = result.stream().filter(r -> "D".equals(r.symbol())).findFirst().orElseThrow();
        assertThat(d.eligibleForSetup()).isFalse();
        assertThat(d.rejectionReason()).contains("OUTSIDE_TOP_3");
    }

    @Test
    void rank_sortedDescBySelectionScore() {
        List<RankCandidateInput> inputs = List.of(
                candidate("LOW",  5.0, 5.0, 5.0, 5.0, false, false, false),
                candidate("HIGH", 9.0, 9.0, 9.0, 9.0, false, false, false),
                candidate("MID",  7.0, 7.0, 7.0, 7.0, false, false, false)
        );
        List<RankedCandidate> eligible = engine.rank(inputs, bullRegime()).stream()
                .filter(RankedCandidate::eligibleForSetup)
                .sorted((a, b) -> b.selectionScore().compareTo(a.selectionScore()))
                .toList();

        assertThat(eligible.get(0).symbol()).isEqualTo("HIGH");
        assertThat(eligible.get(1).symbol()).isEqualTo("MID");
    }

    @Test
    void emptyInput_returnsEmptyList() {
        assertThat(engine.rank(List.of(), bullRegime())).isEmpty();
        assertThat(engine.rank(null, bullRegime())).isEmpty();
    }

    @Test
    void topCandidates_returnsOnlyEligible() {
        List<RankCandidateInput> inputs = List.of(
                candidate("A", 9.0, 8.0, 9.0, 8.0, false, false, false),
                candidate("B", 7.0, 7.0, 7.0, 7.0, false, false, false),
                candidate("X", 8.0, 8.0, 8.0, 8.0, true,  false, false)  // codex vetoed
        );
        List<RankedCandidate> top = engine.topCandidates(inputs, bullRegime(), 2);

        assertThat(top).hasSize(2);
        assertThat(top.stream().noneMatch(r -> "X".equals(r.symbol()))).isTrue();
    }
}
