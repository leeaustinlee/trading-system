package com.austin.trading.engine;

import com.austin.trading.dto.internal.ExecutionDecisionInput;
import com.austin.trading.dto.internal.ExecutionDecisionOutput;
import com.austin.trading.dto.internal.RankedCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionDecisionEngineTests {

    private ExecutionDecisionEngine engine;
    private static final LocalDate DATE = LocalDate.of(2026, 4, 21);

    @BeforeEach
    void setUp() {
        engine = new ExecutionDecisionEngine(new ObjectMapper());
    }

    // ── helper ────────────────────────────────────────────────────────────────

    private RankedCandidate ranked(String symbol, boolean vetoed) {
        return new RankedCandidate(DATE, symbol,
                BigDecimal.valueOf(8.5), BigDecimal.valueOf(7.0),
                BigDecimal.valueOf(8.0), BigDecimal.valueOf(8.0),
                "AI", vetoed, !vetoed, vetoed ? "VETO" : null, "{}");
    }

    private ExecutionDecisionInput input(String symbol, boolean vetoed, String baseAction) {
        return new ExecutionDecisionInput(ranked(symbol, vetoed), baseAction,
                null, null, null, null);
    }

    // ── Rule 1: Codex veto ────────────────────────────────────────────────────

    @Test
    void vetoedCandidate_producesSkipWithCodexVetoReason() {
        ExecutionDecisionOutput out = engine.evaluateOne(input("AAPL", true, "ENTER"));

        assertThat(out.action()).isEqualTo(ExecutionDecisionEngine.ACTION_SKIP);
        assertThat(out.reasonCode()).isEqualTo(ExecutionDecisionEngine.REASON_VETOED);
        assertThat(out.codexVetoed()).isTrue();
    }

    @Test
    void vetoedCandidate_codexCannotForceEntry() {
        // Even if baseAction=ENTER, veto wins
        ExecutionDecisionOutput out = engine.evaluateOne(input("AAPL", true, "ENTER"));
        assertThat(out.action()).isEqualTo(ExecutionDecisionEngine.ACTION_SKIP);
    }

    // ── Rule 2: EXIT / WEAKEN pass-through ───────────────────────────────────

    @Test
    void exitBaseAction_passedThrough() {
        ExecutionDecisionOutput out = engine.evaluateOne(input("TSMC", false, "EXIT"));

        assertThat(out.action()).isEqualTo(ExecutionDecisionEngine.ACTION_EXIT);
        assertThat(out.reasonCode()).isEqualTo("BASE_ACTION_EXIT");
        assertThat(out.codexVetoed()).isFalse();
    }

    @Test
    void weakenBaseAction_passedThrough() {
        ExecutionDecisionOutput out = engine.evaluateOne(input("TSMC", false, "WEAKEN"));

        assertThat(out.action()).isEqualTo(ExecutionDecisionEngine.ACTION_WEAKEN);
        assertThat(out.reasonCode()).isEqualTo("BASE_ACTION_WEAKEN");
    }

    @Test
    void exitBaseAction_lowercase_normalised() {
        ExecutionDecisionOutput out = engine.evaluateOne(input("X", false, "exit"));
        assertThat(out.action()).isEqualTo(ExecutionDecisionEngine.ACTION_EXIT);
    }

    // ── Rule 3: ENTER confirmation ────────────────────────────────────────────

    @Test
    void enterBaseAction_confirmedAsEnter() {
        ExecutionDecisionOutput out = engine.evaluateOne(input("TSMC", false, "ENTER"));

        assertThat(out.action()).isEqualTo(ExecutionDecisionEngine.ACTION_ENTER);
        assertThat(out.reasonCode()).isEqualTo(ExecutionDecisionEngine.REASON_CONFIRMED);
        assertThat(out.codexVetoed()).isFalse();
    }

    @Test
    void skipBaseAction_producesSkip() {
        ExecutionDecisionOutput out = engine.evaluateOne(input("X", false, "SKIP"));

        assertThat(out.action()).isEqualTo(ExecutionDecisionEngine.ACTION_SKIP);
        assertThat(out.reasonCode()).isEqualTo("BASE_ACTION_SKIP");
    }

    @Test
    void restBaseAction_producesSkipWithRestReason() {
        ExecutionDecisionOutput out = engine.evaluateOne(input("X", false, "REST"));

        assertThat(out.action()).isEqualTo(ExecutionDecisionEngine.ACTION_SKIP);
        assertThat(out.reasonCode()).isEqualTo("BASE_ACTION_REST");
    }

    @Test
    void nullBaseAction_producesSkipWithUnknownReason() {
        ExecutionDecisionOutput out = engine.evaluateOne(input("X", false, null));

        assertThat(out.action()).isEqualTo(ExecutionDecisionEngine.ACTION_SKIP);
        assertThat(out.reasonCode()).isEqualTo("BASE_ACTION_UNKNOWN");
    }

    // ── Output field correctness ──────────────────────────────────────────────

    @Test
    void output_symbolAndDateFromRankedCandidate() {
        ExecutionDecisionOutput out = engine.evaluateOne(input("TSMC", false, "ENTER"));

        assertThat(out.symbol()).isEqualTo("TSMC");
        assertThat(out.tradingDate()).isEqualTo(DATE);
    }

    @Test
    void output_upstreamIdsAreNullFromEngine() {
        ExecutionDecisionOutput out = engine.evaluateOne(input("X", false, "ENTER"));

        assertThat(out.regimeDecisionId()).isNull();
        assertThat(out.rankingSnapshotId()).isNull();
        assertThat(out.setupDecisionId()).isNull();
        assertThat(out.timingDecisionId()).isNull();
        assertThat(out.riskDecisionId()).isNull();
    }

    @Test
    void output_payloadContainsKeyFields() {
        ExecutionDecisionOutput out = engine.evaluateOne(input("TSMC", false, "ENTER"));
        assertThat(out.payloadJson()).contains("TSMC").contains("ENTER").contains("CONFIRMED");
    }

    // ── Batch evaluate ────────────────────────────────────────────────────────

    @Test
    void evaluate_batch_returnsAllDecisions() {
        List<ExecutionDecisionInput> inputs = List.of(
                input("A", false, "ENTER"),
                input("B", true,  "ENTER"),
                input("C", false, "SKIP")
        );
        List<ExecutionDecisionOutput> results = engine.evaluate(inputs);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).action()).isEqualTo("ENTER");
        assertThat(results.get(1).action()).isEqualTo("SKIP");
        assertThat(results.get(2).action()).isEqualTo("SKIP");
    }

    @Test
    void evaluate_nullInput_returnsEmptyList() {
        assertThat(engine.evaluate(null)).isEmpty();
    }

    @Test
    void evaluateOne_nullInput_returnsNull() {
        assertThat(engine.evaluateOne(null)).isNull();
    }

    // ── Null rankedCandidate ──────────────────────────────────────────────────

    @Test
    void nullRankedCandidate_withEnterBase_producesEnter() {
        ExecutionDecisionInput in = new ExecutionDecisionInput(
                null, "ENTER", null, null, null, null);
        ExecutionDecisionOutput out = engine.evaluateOne(in);

        assertThat(out.action()).isEqualTo(ExecutionDecisionEngine.ACTION_ENTER);
        assertThat(out.symbol()).isEqualTo("UNKNOWN");
        assertThat(out.tradingDate()).isNull();
    }
}
