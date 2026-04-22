package com.austin.trading.service;

import com.austin.trading.dto.internal.ExecutionDecisionInput;
import com.austin.trading.dto.internal.ExecutionDecisionOutput;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.engine.ExecutionDecisionEngine;
import com.austin.trading.repository.ExecutionDecisionLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0.6 Execution Layer — persistence + round-trip integration tests.
 */
@SpringBootTest
@ActiveProfiles("integration")
class ExecutionDecisionServiceIntegrationTests {

    @Autowired ExecutionDecisionService       service;
    @Autowired ExecutionDecisionLogRepository execRepo;

    private LocalDate uniqueDate;

    @BeforeEach
    void setUp() {
        uniqueDate = LocalDate.of(2006, 1, 1).plusDays(System.nanoTime() % 10_000);
    }

    private RankedCandidate ranked(String symbol, boolean vetoed) {
        return new RankedCandidate(uniqueDate, symbol,
                BigDecimal.valueOf(8.5), BigDecimal.valueOf(7.0),
                BigDecimal.valueOf(8.0), BigDecimal.valueOf(8.0),
                "AI", vetoed, !vetoed, vetoed ? "VETO" : null, "{}");
    }

    private ExecutionDecisionInput enterInput(String symbol) {
        return new ExecutionDecisionInput(ranked(symbol, false), "ENTER",
                null, null, null, null);
    }

    private ExecutionDecisionInput skipInput(String symbol) {
        return new ExecutionDecisionInput(ranked(symbol, false), "SKIP",
                null, null, null, null);
    }

    private ExecutionDecisionInput vetoInput(String symbol) {
        return new ExecutionDecisionInput(ranked(symbol, true), "ENTER",
                null, null, null, null);
    }

    // ── Persist and retrieve ──────────────────────────────────────────────────

    @Test
    void logDecision_persistsAndReturnsEnter() {
        ExecutionDecisionOutput out = service.logDecision(enterInput("TSMC"), uniqueDate);

        assertThat(out.action()).isEqualTo(ExecutionDecisionEngine.ACTION_ENTER);
        assertThat(out.reasonCode()).isEqualTo(ExecutionDecisionEngine.REASON_CONFIRMED);
        assertThat(out.symbol()).isEqualTo("TSMC");
        assertThat(out.tradingDate()).isEqualTo(uniqueDate);

        // Verify persisted
        assertThat(execRepo.findEnterByTradingDate(uniqueDate))
                .anyMatch(e -> "TSMC".equals(e.getSymbol()) && "ENTER".equals(e.getAction()));
    }

    @Test
    void logDecision_vetoedCandidate_persistsSkip() {
        ExecutionDecisionOutput out = service.logDecision(vetoInput("VETO_STOCK"), uniqueDate);

        assertThat(out.action()).isEqualTo(ExecutionDecisionEngine.ACTION_SKIP);
        assertThat(out.codexVetoed()).isTrue();
        assertThat(execRepo.findByTradingDate(uniqueDate))
                .anyMatch(e -> "VETO_STOCK".equals(e.getSymbol()) && e.isCodexVetoed());
    }

    @Test
    void logDecisions_batch_persistsAll() {
        List<ExecutionDecisionInput> inputs = List.of(
                enterInput("AA"),
                skipInput("BB"),
                vetoInput("CC")
        );
        List<ExecutionDecisionOutput> results = service.logDecisions(inputs, uniqueDate);

        assertThat(results).hasSize(3);

        List<String> enterSymbols = execRepo.findEnterByTradingDate(uniqueDate)
                .stream().map(e -> e.getSymbol()).toList();
        assertThat(enterSymbols).contains("AA");
        assertThat(enterSymbols).doesNotContain("BB", "CC");
    }

    @Test
    void getEnterByDate_returnsOnlyEnterDecisions() {
        service.logDecisions(List.of(enterInput("E1"), skipInput("S1")), uniqueDate);

        List<ExecutionDecisionOutput> enters = service.getEnterByDate(uniqueDate);
        assertThat(enters).allMatch(d -> ExecutionDecisionEngine.ACTION_ENTER.equals(d.action()));
        assertThat(enters.stream().map(ExecutionDecisionOutput::symbol).toList()).contains("E1");
    }

    @Test
    void getByDate_returnsAllDecisions() {
        service.logDecisions(List.of(enterInput("X1"), skipInput("X2")), uniqueDate);

        List<ExecutionDecisionOutput> all = service.getByDate(uniqueDate);
        assertThat(all).hasSizeGreaterThanOrEqualTo(2);
        assertThat(all.stream().map(ExecutionDecisionOutput::symbol).toList())
                .containsAll(List.of("X1", "X2"));
    }
}
