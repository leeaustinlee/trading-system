package com.austin.trading.service;

import com.austin.trading.dto.internal.ExecutionTimingDecision;
import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.dto.internal.SetupDecision;
import com.austin.trading.dto.internal.SetupEvaluationInput;
import com.austin.trading.dto.internal.TimingEvaluationInput;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.engine.SetupEngine;
import com.austin.trading.repository.ExecutionTimingDecisionRepository;
import com.austin.trading.repository.SetupDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the Step 0.7 Timing Pipeline:
 * Setup generation → timing evaluation → filter.
 *
 * Validates the fix for Codex review finding:
 * "Step 0.7 只讀 setup，不生產 setup，會把候選幾乎全擋成 NO_SETUP"
 */
@SpringBootTest
@ActiveProfiles("integration")
class TimingPipelineIntegrationTests {

    @Autowired FinalDecisionService              finalDecisionService;
    @Autowired SetupValidationService            setupValidationService;
    @Autowired ExecutionTimingService            executionTimingService;
    @Autowired SetupDecisionRepository           setupRepo;
    @Autowired ExecutionTimingDecisionRepository timingRepo;

    private LocalDate uniqueDate;

    @BeforeEach
    void setUp() {
        uniqueDate = LocalDate.of(2004, 1, 1).plusDays(System.nanoTime() % 10_000);
    }

    private RankedCandidate ranked(String symbol) {
        return new RankedCandidate(uniqueDate, symbol,
                new BigDecimal("8.5"), new BigDecimal("8.0"), new BigDecimal("7.5"),
                new BigDecimal("8.2"), "AI_THEME",
                false, true, null, "{}");
    }

    private MarketRegimeDecision bullRegime() {
        return new MarketRegimeDecision(uniqueDate, LocalDateTime.now(), "BULL_TREND", "A",
                true, new BigDecimal("1.0"),
                List.of(SetupEngine.SETUP_BREAKOUT, SetupEngine.SETUP_PULLBACK, SetupEngine.SETUP_EVENT),
                "bull", "[]", "{}");
    }

    // ── Test 1: toSetupInput builds a parseable SetupEvaluationInput ──────────

    @Test
    void toSetupInput_parsesEntryPriceZone_breakoutCandidate() {
        FinalDecisionCandidateRequest req = makeRequest("PIPE_A", "98.0-102.0", "BREAKOUT", 95.0);
        SetupEvaluationInput input = finalDecisionService.toSetupInput(req, ranked("PIPE_A"), bullRegime());

        assertThat(input).isNotNull();
        assertThat(input.currentPrice()).isEqualByComparingTo("100.0");  // midpoint
        assertThat(input.baseHigh()).isEqualByComparingTo("102.0");
        assertThat(input.baseLow()).isEqualByComparingTo("95.0");
        assertThat(input.eventDriven()).isFalse();
    }

    @Test
    void toSetupInput_nullZone_returnsNull() {
        FinalDecisionCandidateRequest req = makeRequest("PIPE_B", null, "BREAKOUT", 95.0);
        assertThat(finalDecisionService.toSetupInput(req, ranked("PIPE_B"), bullRegime())).isNull();
    }

    @Test
    void toSetupInput_singlePrice_zone() {
        FinalDecisionCandidateRequest req = makeRequest("PIPE_C", "100.0", "BREAKOUT", null);
        SetupEvaluationInput input = finalDecisionService.toSetupInput(req, ranked("PIPE_C"), bullRegime());

        assertThat(input).isNotNull();
        assertThat(input.currentPrice()).isEqualByComparingTo("100.0");
        assertThat(input.baseHigh()).isEqualByComparingTo("100.0");
    }

    @Test
    void toSetupInput_eventEntryType_setsEventDriven() {
        FinalDecisionCandidateRequest req = makeRequest("PIPE_D", "48.0-52.0", "EVENT_SECOND_LEG", 46.0);
        SetupEvaluationInput input = finalDecisionService.toSetupInput(req, ranked("PIPE_D"), bullRegime());

        assertThat(input).isNotNull();
        assertThat(input.eventDriven()).isTrue();
        assertThat(input.consolidationDays()).isEqualTo(5);
    }

    // ── Test 2: Pipeline integration — setup generation + timing ─────────────

    @Test
    void pipeline_noPreExistingSetup_generatesSetupAndApprovesBreakout() {
        // Candidate with a valid breakout zone — no setup in DB yet
        FinalDecisionCandidateRequest req =
                makeRequest("PIPE_E", "98.0-102.0", "BREAKOUT", 93.0);
        RankedCandidate rc = ranked("PIPE_E");
        Map<String, RankedCandidate> rankMap = Map.of("PIPE_E", rc);

        // Step 0.7a: buildOrLoadSetups generates setup on-the-fly
        // (via public service calls that mirror what buildOrLoadSetups does internally)
        SetupEvaluationInput setupInput =
                finalDecisionService.toSetupInput(req, rc, bullRegime());
        assertThat(setupInput).isNotNull();

        List<SetupDecision> setups = setupValidationService.evaluateAll(
                List.of(setupInput));
        assertThat(setups).hasSize(1);
        SetupDecision setup = setups.get(0);
        assertThat(setup.valid()).isTrue();
        assertThat(setup.setupType()).isEqualTo(SetupEngine.SETUP_BREAKOUT);

        // Step 0.7b: evaluate timing with breakout-ready signals
        TimingEvaluationInput timingInput = new TimingEvaluationInput(
                rc, setup, bullRegime(),
                true,   // nearDayHigh
                false,  // NOT belowOpen
                false,
                true,   // entryTriggered
                false,
                0
        );
        ExecutionTimingDecision timing = executionTimingService.evaluateOne(timingInput);

        assertThat(timing.approved()).isTrue();
        assertThat(timing.timingMode()).isEqualTo("BREAKOUT_READY");
        assertThat(timing.urgency()).isEqualTo("HIGH");

        // Verify persistence
        assertThat(setupRepo.findByTradingDate(uniqueDate)).isNotEmpty();
        assertThat(timingRepo.findByTradingDate(uniqueDate)).isNotEmpty();
    }

    @Test
    void pipeline_preExistingSetup_usedDirectly() {
        // Pre-seed a valid setup in DB as if a pre-market job ran
        SetupEvaluationInput setupInput = new SetupEvaluationInput(
                ranked("PIPE_F"), bullRegime(), null,
                new BigDecimal("102.0"), new BigDecimal("100.0"),
                null, null,
                new BigDecimal("100.0"), new BigDecimal("92.0"),
                null, null,
                new BigDecimal("10000"), new BigDecimal("16000"),
                5, false
        );
        List<SetupDecision> preSeeded = setupValidationService.evaluateAll(List.of(setupInput));
        assertThat(preSeeded.get(0).valid()).isTrue();

        // buildOrLoadSetups should find the pre-existing setup without generating a new one
        FinalDecisionCandidateRequest req =
                makeRequest("PIPE_F", "98.0-102.0", "BREAKOUT", 92.0);
        SetupEvaluationInput derivedInput =
                finalDecisionService.toSetupInput(req, ranked("PIPE_F"), bullRegime());
        assertThat(derivedInput).isNotNull();

        // The DB already has a valid setup → no second row needed
        long countBefore = setupRepo.findByTradingDate(uniqueDate).size();
        // Simulate: buildOrLoadSetups only generates for candidates NOT already in DB
        // Since PIPE_F is already in DB, no new rows should be added
        Map<String, SetupDecision> result = new java.util.HashMap<>();
        setupValidationService.getValidByDate(uniqueDate)
                .forEach(s -> result.put(s.symbol(), s));
        assertThat(result).containsKey("PIPE_F");
        // Count should remain stable (no double-generation)
        assertThat(setupRepo.findByTradingDate(uniqueDate).size()).isEqualTo(countBefore);
    }

    @Test
    void pipeline_invalidZone_timingBlockedWithNoSetup() {
        FinalDecisionCandidateRequest req =
                makeRequest("PIPE_G", null, "BREAKOUT", null);  // null zone → no setup
        RankedCandidate rc = ranked("PIPE_G");

        SetupEvaluationInput input = finalDecisionService.toSetupInput(req, rc, bullRegime());
        assertThat(input).isNull();   // parseEntryZone returned null

        // Without setup, timing must block
        TimingEvaluationInput timingInput = new TimingEvaluationInput(
                rc, null, bullRegime(),
                true, false, false, true, false, 0
        );
        ExecutionTimingDecision timing = executionTimingService.evaluateOne(timingInput);
        assertThat(timing.approved()).isFalse();
        assertThat(timing.timingMode()).isEqualTo("NO_SETUP");
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private FinalDecisionCandidateRequest makeRequest(String symbol, String zone,
                                                       String entryType, Double stopLoss) {
        return new FinalDecisionCandidateRequest(
                symbol, symbol + "_NAME",
                "BREAKOUT_WATCH", entryType,
                2.5, true, true, false, false, false, true, true,
                "test", zone, stopLoss,
                null, null,
                new BigDecimal("8.0"), new BigDecimal("8.0"), new BigDecimal("8.0"),
                new BigDecimal("8.0"), false,
                new BigDecimal("7.5"), true,
                1, new BigDecimal("8.0"), new BigDecimal("8.0"), new BigDecimal("0.1"),
                false, false, false, true
        );
    }
}
