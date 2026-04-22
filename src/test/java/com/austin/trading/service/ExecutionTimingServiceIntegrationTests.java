package com.austin.trading.service;

import com.austin.trading.dto.internal.ExecutionTimingDecision;
import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.dto.internal.SetupDecision;
import com.austin.trading.dto.internal.TimingEvaluationInput;
import com.austin.trading.engine.ExecutionTimingEngine;
import com.austin.trading.engine.SetupEngine;
import com.austin.trading.repository.ExecutionTimingDecisionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0.4 Timing Layer — persistence + round-trip integration tests.
 */
@SpringBootTest
@ActiveProfiles("integration")
class ExecutionTimingServiceIntegrationTests {

    @Autowired ExecutionTimingService            service;
    @Autowired ExecutionTimingDecisionRepository timingRepo;

    private LocalDate uniqueDate;

    @BeforeEach
    void setUp() {
        uniqueDate = LocalDate.of(2003, 1, 1).plusDays(System.nanoTime() % 10_000);
    }

    private RankedCandidate candidate(String symbol) {
        return new RankedCandidate(uniqueDate, symbol,
                new BigDecimal("8.0"), new BigDecimal("7.5"), new BigDecimal("7.0"),
                new BigDecimal("7.8"), "AI_THEME",
                false, true, null, "{}");
    }

    private SetupDecision validBreakout(String symbol) {
        return new SetupDecision(uniqueDate, symbol, SetupEngine.SETUP_BREAKOUT, true,
                new BigDecimal("98.0"), new BigDecimal("102.0"),
                new BigDecimal("100.0"), new BigDecimal("95.0"),
                new BigDecimal("96.0"), new BigDecimal("107.0"), new BigDecimal("113.0"),
                "MA5_TRAIL", 10, null, "{}");
    }

    private MarketRegimeDecision bullRegime() {
        return new MarketRegimeDecision(uniqueDate, LocalDateTime.now(), "BULL_TREND", "A",
                true, new BigDecimal("1.0"),
                List.of(SetupEngine.SETUP_BREAKOUT, SetupEngine.SETUP_PULLBACK, SetupEngine.SETUP_EVENT),
                "bull", "[]", "{}");
    }

    @Test
    void evaluateOne_approved_persistsAndReturns() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("TMG_A"), validBreakout("TMG_A"), bullRegime(),
                true, false, false, true, false, 0);

        ExecutionTimingDecision d = service.evaluateOne(in);

        assertThat(d).isNotNull();
        assertThat(d.approved()).isTrue();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_BREAKOUT_READY);
        assertThat(d.urgency()).isEqualTo(ExecutionTimingEngine.URGENCY_HIGH);

        var rows = timingRepo.findByTradingDate(uniqueDate);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isApproved()).isTrue();
        assertThat(rows.get(0).getTimingMode()).isEqualTo(ExecutionTimingEngine.MODE_BREAKOUT_READY);
    }

    @Test
    void evaluateOne_blocked_persistsWithReason() {
        TimingEvaluationInput in = new TimingEvaluationInput(
                candidate("TMG_B"), null, bullRegime(),
                false, false, false, false, false, 0);

        ExecutionTimingDecision d = service.evaluateOne(in);

        assertThat(d.approved()).isFalse();
        assertThat(d.timingMode()).isEqualTo(ExecutionTimingEngine.MODE_NO_SETUP);
        assertThat(d.rejectionReason()).isEqualTo("NO_VALID_SETUP");

        var rows = timingRepo.findByTradingDate(uniqueDate);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRejectionReason()).isEqualTo("NO_VALID_SETUP");
    }

    @Test
    void evaluateAll_persistsAll_countCorrect() {
        List<TimingEvaluationInput> inputs = List.of(
                new TimingEvaluationInput(candidate("TMG_C"), validBreakout("TMG_C"), bullRegime(),
                        true, false, false, true, false, 0),
                new TimingEvaluationInput(candidate("TMG_D"), null, bullRegime(),
                        false, false, false, false, false, 0)
        );

        List<ExecutionTimingDecision> results = service.evaluateAll(inputs, uniqueDate);

        assertThat(results).hasSize(2);
        assertThat(results.stream().filter(ExecutionTimingDecision::approved).count()).isEqualTo(1);

        var rows = timingRepo.findByTradingDate(uniqueDate);
        assertThat(rows).hasSize(2);
    }

    @Test
    void getApprovedByDate_returnsOnlyApproved() {
        TimingEvaluationInput approved = new TimingEvaluationInput(
                candidate("TMG_E"), validBreakout("TMG_E"), bullRegime(),
                true, false, false, true, false, 0);
        TimingEvaluationInput blocked = new TimingEvaluationInput(
                candidate("TMG_F"), null, bullRegime(),
                false, false, false, false, false, 0);

        service.evaluateAll(List.of(approved, blocked), uniqueDate);

        List<ExecutionTimingDecision> approvedList = service.getApprovedByDate(uniqueDate);
        assertThat(approvedList).isNotEmpty();
        assertThat(approvedList.stream().allMatch(ExecutionTimingDecision::approved)).isTrue();
    }
}
