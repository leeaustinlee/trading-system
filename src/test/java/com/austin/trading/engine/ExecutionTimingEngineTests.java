package com.austin.trading.engine;

import com.austin.trading.dto.internal.ExecutionTimingDecision;
import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.dto.internal.SetupDecision;
import com.austin.trading.dto.internal.TimingEvaluationInput;
import com.austin.trading.service.ScoreConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-unit tests for {@link ExecutionTimingEngine}: no DB, ScoreConfigService mocked.
 */
class ExecutionTimingEngineTests {

    private ExecutionTimingEngine engine;
    private static final LocalDate DATE = LocalDate.of(2026, 4, 21);

    @BeforeEach
    void setUp() {
        ScoreConfigService config = mock(ScoreConfigService.class);
        when(config.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        engine = new ExecutionTimingEngine(config, new ObjectMapper());
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private RankedCandidate candidate(String symbol) {
        return new RankedCandidate(DATE, symbol,
                new BigDecimal("8.0"), new BigDecimal("7.5"), new BigDecimal("7.0"),
                new BigDecimal("7.8"), "AI_THEME",
                false, true, null, "{}");
    }

    private SetupDecision validSetup(String symbol, String type) {
        return new SetupDecision(DATE, symbol, type, true,
                new BigDecimal("98.0"), new BigDecimal("102.0"),
                new BigDecimal("100.0"), new BigDecimal("95.0"),
                new BigDecimal("96.0"), new BigDecimal("107.0"), new BigDecimal("113.0"),
                "MA5_TRAIL", 10, null, "{}");
    }

    private MarketRegimeDecision bullRegime() {
        return new MarketRegimeDecision(DATE, LocalDateTime.now(), "BULL_TREND", "A",
                true, new BigDecimal("1.0"),
                List.of(SetupEngine.SETUP_BREAKOUT, SetupEngine.SETUP_PULLBACK, SetupEngine.SETUP_EVENT),
                "bull", "[]", "{}");
    }

    private MarketRegimeDecision rangeRegime() {
        return new MarketRegimeDecision(DATE, LocalDateTime.now(), "RANGE_CHOP", "B",
                true, new BigDecimal("0.8"),
                List.of(SetupEngine.SETUP_PULLBACK, SetupEngine.SETUP_EVENT),
                "range", "[]", "{}");
    }

    private MarketRegimeDecision weakRegime() {
        return new MarketRegimeDecision(DATE, LocalDateTime.now(), "WEAK_DOWNTREND", "C",
                true, new BigDecimal("0.5"),
                List.of(SetupEngine.SETUP_PULLBACK),
                "weak", "[]", "{}");
    }

    // ── No-setup / null guard ──────────────────────────────────────────────

    @Test
    void nullSetup_blocksWithNoSetup() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2330"), null, bullRegime(),
                true, false, false, true, false, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isFalse();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_NO_SETUP);
        assertThat(d.rejectionReason()).isEqualTo("NO_VALID_SETUP");
    }

    @Test
    void invalidSetup_blocksWithNoSetup() {
        SetupDecision invalid = new SetupDecision(DATE, "2330", null, false,
                null, null, null, null, null, null, null, null, null,
                "REGIME_BLOCKS_ALL_SETUPS", "{}");
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2330"), invalid, bullRegime(),
                true, false, false, false, false, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isFalse();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_NO_SETUP);
    }

    // ── Stale signal ───────────────────────────────────────────────────────

    @Test
    void staleBreakout_blocked() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2330"), validSetup("2330", SetupEngine.SETUP_BREAKOUT), bullRegime(),
                true, false, false, true, false,
                4);  // > stale_days default 3

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isFalse();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_STALE);
        assertThat(d.staleSignal()).isTrue();
        assertThat(d.rejectionReason()).contains("STALE_SIGNAL");
    }

    @Test
    void stalePullback_blocked() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2382"), validSetup("2382", SetupEngine.SETUP_PULLBACK), bullRegime(),
                false, false, false, false, false,
                6);  // > stale_days default 5

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isFalse();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_STALE);
    }

    // ── Breakout timing ────────────────────────────────────────────────────

    @Test
    void breakout_entryTriggered_approvedHighUrgency() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2330"), validSetup("2330", SetupEngine.SETUP_BREAKOUT), bullRegime(),
                true, false, false,
                true,   // entryTriggered
                false, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isTrue();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_BREAKOUT_READY);
        assertThat(d.urgency()).isEqualTo(ExecutionTimingEngine.URGENCY_HIGH);
    }

    @Test
    void breakout_nearDayHighNotBelowOpen_approvedMediumUrgency() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2330"), validSetup("2330", SetupEngine.SETUP_BREAKOUT), bullRegime(),
                true,   // nearDayHigh
                false,  // NOT belowOpen
                false, false, false, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isTrue();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_BREAKOUT_READY);
        assertThat(d.urgency()).isEqualTo(ExecutionTimingEngine.URGENCY_MEDIUM);
    }

    @Test
    void breakout_notNearHighAndBelowOpen_wait() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2330"), validSetup("2330", SetupEngine.SETUP_BREAKOUT), bullRegime(),
                false,  // NOT nearDayHigh
                true,   // belowOpen
                false, false, false, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isFalse();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_WAIT);
        assertThat(d.rejectionReason()).contains("BREAKOUT:WAITING_FOR_ENTRY_SIGNAL");
    }

    @Test
    void breakout_nearHighButBelowOpen_wait() {
        // nearDayHigh=true but belowOpen=true → not valid for medium urgency breakout
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2330"), validSetup("2330", SetupEngine.SETUP_BREAKOUT), bullRegime(),
                true, true, false, false, false, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isFalse();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_WAIT);
    }

    // ── Pullback timing ────────────────────────────────────────────────────

    @Test
    void pullback_notBelowOpen_approvedMediumUrgency() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2382"), validSetup("2382", SetupEngine.SETUP_PULLBACK), bullRegime(),
                false,  // not nearDayHigh
                false,  // NOT belowOpen
                false,  // not below prev close
                false, false, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isTrue();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_PULLBACK_BOUNCE);
        assertThat(d.urgency()).isEqualTo(ExecutionTimingEngine.URGENCY_MEDIUM);
    }

    @Test
    void pullback_notBelowOpenButBelowPrevClose_approvedLowUrgency() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2382"), validSetup("2382", SetupEngine.SETUP_PULLBACK), bullRegime(),
                false, false,
                true,   // belowPrevClose → low urgency
                false, false, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isTrue();
        assertThat(d.urgency()).isEqualTo(ExecutionTimingEngine.URGENCY_LOW);
    }

    @Test
    void pullback_belowOpen_wait() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2382"), validSetup("2382", SetupEngine.SETUP_PULLBACK), bullRegime(),
                false,
                true,   // belowOpen
                false, false, false, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isFalse();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_WAIT);
        assertThat(d.rejectionReason()).contains("PULLBACK:PRICE_STILL_DECLINING");
    }

    // ── Event timing ───────────────────────────────────────────────────────

    @Test
    void event_notBelowOpen_approved() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2317"), validSetup("2317", SetupEngine.SETUP_EVENT), bullRegime(),
                false, false, false, false, false, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isTrue();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_EVENT_LAUNCH);
        assertThat(d.urgency()).isEqualTo(ExecutionTimingEngine.URGENCY_MEDIUM);
    }

    @Test
    void event_volumeSpikeAndNearHigh_highUrgency() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2317"), validSetup("2317", SetupEngine.SETUP_EVENT), bullRegime(),
                true,   // nearDayHigh
                false,
                false, false,
                true,   // volumeSpike
                0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isTrue();
        assertThat(d.urgency()).isEqualTo(ExecutionTimingEngine.URGENCY_HIGH);
    }

    @Test
    void event_belowOpen_wait() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2317"), validSetup("2317", SetupEngine.SETUP_EVENT), bullRegime(),
                false,
                true,   // belowOpen
                false, false, false, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isFalse();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_WAIT);
        assertThat(d.rejectionReason()).contains("EVENT:BASE_BROKEN_DOWN");
    }

    // ── Regime urgency cap ─────────────────────────────────────────────────

    @Test
    void weakRegime_capsHighUrgencyToMedium() {
        // riskMultiplier=0.5 < 0.7 → urgency capped to LOW
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2330"), validSetup("2330", SetupEngine.SETUP_BREAKOUT), weakRegime(),
                true, false, false, true, false, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isTrue();
        assertThat(d.urgency()).isEqualTo(ExecutionTimingEngine.URGENCY_LOW);
    }

    @Test
    void rangeRegime_capsHighToMediumUrgency() {
        // riskMultiplier=0.8 → caps HIGH to MEDIUM
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2317"), validSetup("2317", SetupEngine.SETUP_EVENT), rangeRegime(),
                true, false, false, false, true, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.approved()).isTrue();
        assertThat(d.urgency()).isEqualTo(ExecutionTimingEngine.URGENCY_MEDIUM);
    }

    // ── Batch evaluate ─────────────────────────────────────────────────────

    @Test
    void evaluate_list_returnsOnePerInput() {
        List<TimingEvaluationInput> inputs = List.of(
                new TimingEvaluationInput(candidate("A"), validSetup("A", SetupEngine.SETUP_BREAKOUT),
                        bullRegime(), true, false, false, true, false, 0),
                new TimingEvaluationInput(candidate("B"), null, bullRegime(),
                        false, false, false, false, false, 0)
        );

        List<ExecutionTimingDecision> results = engine.evaluate(inputs);
        assertThat(results).hasSize(2);
        assertThat(results.get(0).approved()).isTrue();
        assertThat(results.get(1).approved()).isFalse();
    }

    // ── Payload ────────────────────────────────────────────────────────────

    @Test
    void payloadJson_isParseable() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("2330"), validSetup("2330", SetupEngine.SETUP_BREAKOUT), bullRegime(),
                true, false, false, true, false, 0);

        ExecutionTimingDecision d = engine.evaluateOne(in);
        assertThat(d.payloadJson()).isNotNull().startsWith("{").contains("mode").contains("urgency");
    }
}
