package com.austin.trading.service;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.dto.internal.SetupDecision;
import com.austin.trading.dto.internal.SetupEvaluationInput;
import com.austin.trading.engine.SetupEngine;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0.3 Setup Layer — persistence + round-trip integration tests.
 */
@SpringBootTest
@ActiveProfiles("integration")
class SetupValidationServiceIntegrationTests {

    @Autowired SetupValidationService  service;
    @Autowired SetupDecisionRepository setupRepo;

    private LocalDate uniqueDate;

    @BeforeEach
    void setUp() {
        uniqueDate = LocalDate.of(2002, 1, 1).plusDays(System.nanoTime() % 10_000);
    }

    private RankedCandidate candidate(String symbol) {
        return new RankedCandidate(uniqueDate, symbol,
                new BigDecimal("7.5"), new BigDecimal("7.0"), new BigDecimal("6.0"),
                new BigDecimal("7.0"), "AI_THEME",
                false, true, null, "{}");
    }

    private MarketRegimeDecision bullRegime() {
        return new MarketRegimeDecision(uniqueDate, LocalDateTime.now(), "BULL_TREND", "A",
                true, new BigDecimal("1.0"),
                List.of(SetupEngine.SETUP_BREAKOUT, SetupEngine.SETUP_PULLBACK, SetupEngine.SETUP_EVENT),
                "bull", "[]", "{}");
    }

    @Test
    void evaluateOne_valid_persistsAndReturns() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("SVC_A"), bullRegime(), null,
                new BigDecimal("102.0"), new BigDecimal("100.0"),
                null, null,
                new BigDecimal("100.0"), new BigDecimal("90.0"),
                null, null,
                new BigDecimal("10000"), new BigDecimal("16000"),
                5, false
        );

        SetupDecision d = service.evaluateOne(in);

        assertThat(d).isNotNull();
        assertThat(d.valid()).isTrue();
        assertThat(d.setupType()).isEqualTo(SetupEngine.SETUP_BREAKOUT);
        assertThat(d.idealEntryPrice()).isEqualByComparingTo("100.0");

        var rows = setupRepo.findByTradingDate(uniqueDate);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isValid()).isTrue();
        assertThat(rows.get(0).getSetupType()).isEqualTo(SetupEngine.SETUP_BREAKOUT);
    }

    @Test
    void evaluateOne_invalid_persistsWithReason() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("SVC_B"), bullRegime(), null,
                null,  // no price
                null, null, null, null, null, null, null, null, null, 0, false
        );

        SetupDecision d = service.evaluateOne(in);

        assertThat(d.valid()).isFalse();
        assertThat(d.rejectionReason()).isEqualTo("MISSING_CURRENT_PRICE");

        var rows = setupRepo.findByTradingDate(uniqueDate);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getRejectionReason()).isEqualTo("MISSING_CURRENT_PRICE");
    }

    @Test
    void evaluateAll_multipleInputs_persistsAll() {
        SetupEvaluationInput valid = new SetupEvaluationInput(
                candidate("SVC_C"), bullRegime(), null,
                new BigDecimal("102.0"), new BigDecimal("100.0"),
                null, null,
                new BigDecimal("100.0"), new BigDecimal("90.0"),
                null, null,
                new BigDecimal("10000"), new BigDecimal("16000"),
                5, false
        );
        SetupEvaluationInput invalid = new SetupEvaluationInput(
                candidate("SVC_D"), bullRegime(), null,
                null, null, null, null, null, null, null, null, null, null, 0, false
        );

        List<SetupDecision> results = service.evaluateAll(List.of(valid, invalid));

        assertThat(results).hasSize(2);
        assertThat(results.stream().filter(SetupDecision::valid).count()).isEqualTo(1);

        var rows = setupRepo.findByTradingDate(uniqueDate);
        assertThat(rows).hasSize(2);
    }

    @Test
    void getValidByDate_returnsOnlyValidRows() {
        SetupEvaluationInput in = new SetupEvaluationInput(
                candidate("SVC_E"), bullRegime(), null,
                new BigDecimal("105.0"), new BigDecimal("103.0"),
                null, null,
                new BigDecimal("103.0"), new BigDecimal("95.0"),
                null, null,
                new BigDecimal("10000"), new BigDecimal("16000"),
                3, false
        );
        service.evaluateOne(in);

        List<SetupDecision> valid = service.getValidByDate(uniqueDate);
        assertThat(valid).isNotEmpty();
        assertThat(valid.stream().allMatch(SetupDecision::valid)).isTrue();
    }
}
