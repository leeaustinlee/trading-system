package com.austin.trading.service;

import com.austin.trading.dto.internal.MarketRegimeDecision;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.dto.request.FinalDecisionCandidateRequest;
import com.austin.trading.repository.StockRankingSnapshotRepository;
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
 * P0.2 Ranking Layer — persistence + round-trip integration tests.
 *
 * Each test uses a unique trading_date to avoid cross-test pollution.
 */
@SpringBootTest
@ActiveProfiles("test")
class StockRankingServiceIntegrationTests {

    @Autowired StockRankingService           service;
    @Autowired StockRankingSnapshotRepository snapshotRepo;

    private LocalDate uniqueDate;

    @BeforeEach
    void setUp() {
        uniqueDate = LocalDate.of(2001, 1, 1).plusDays(System.nanoTime() % 10_000);
    }

    private MarketRegimeDecision bullRegime() {
        return new MarketRegimeDecision(
                uniqueDate, LocalDateTime.now(), "BULL_TREND", "A",
                true, new BigDecimal("1.0"),
                List.of("BREAKOUT_CONTINUATION", "PULLBACK_CONFIRMATION"),
                "regime=BULL_TREND", "[]", "{}"
        );
    }

    private FinalDecisionCandidateRequest scoredCandidate(String symbol,
                                                           double java_, double claude,
                                                           double base, double theme) {
        return new FinalDecisionCandidateRequest(
                symbol, symbol + "名稱", "MOMENTUM", "BREAKOUT",
                2.0, true, true,
                false, false, false, true, true,
                "thesis", "100-102",
                95.0, 110.0, 120.0,
                new BigDecimal(java_), new BigDecimal(claude), null,
                new BigDecimal(java_), false,
                new BigDecimal(base), true,
                1, new BigDecimal(theme),
                new BigDecimal(java_), BigDecimal.ZERO,
                false, false, false, false
        );
    }

    @Test
    void rank_persistsSnapshotsAndReturnsRanked() {
        List<FinalDecisionCandidateRequest> candidates = List.of(
                scoredCandidate("TEST_A", 8.0, 7.5, 7.0, 6.0),
                scoredCandidate("TEST_B", 6.0, 5.0, 5.5, 4.0)
        );

        List<RankedCandidate> ranked = service.rank(candidates, uniqueDate, bullRegime());

        assertThat(ranked).hasSize(2);

        // Verify persistence
        var snapshots = snapshotRepo.findByTradingDate(uniqueDate);
        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.stream().map(s -> s.getSymbol()))
                .containsExactlyInAnyOrder("TEST_A", "TEST_B");
    }

    @Test
    void rank_eligibleCandidateHasScoreAndBreakdown() {
        var candidates = List.of(scoredCandidate("TEST_C", 8.0, 7.0, 8.0, 7.0));

        List<RankedCandidate> ranked = service.rank(candidates, uniqueDate, bullRegime());

        assertThat(ranked).hasSize(1);
        RankedCandidate r = ranked.get(0);
        assertThat(r.eligibleForSetup()).isTrue();
        assertThat(r.selectionScore()).isGreaterThan(BigDecimal.ZERO);
        assertThat(r.scoreBreakdownJson()).isNotBlank();

        var snap = snapshotRepo.findByTradingDate(uniqueDate);
        assertThat(snap.get(0).isEligibleForSetup()).isTrue();
        assertThat(snap.get(0).getScoreBreakdownJson()).contains("selectionScore");
    }

    @Test
    void rank_emptyInput_returnsEmpty() {
        List<RankedCandidate> result = service.rank(List.of(), uniqueDate, bullRegime());
        assertThat(result).isEmpty();
    }

    @Test
    void rank_nullRegime_doesNotThrow() {
        var candidates = List.of(scoredCandidate("TEST_D", 7.0, 6.0, 6.0, 5.0));
        List<RankedCandidate> result = service.rank(candidates, uniqueDate, null);
        assertThat(result).hasSize(1);
    }

    @Test
    void getEligibleForToday_returnsEligibles() {
        // This test only verifies no exception; result depends on today's DB state.
        assertThat(service.getEligibleForToday()).isNotNull();
    }
}
