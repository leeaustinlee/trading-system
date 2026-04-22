package com.austin.trading.service;

import com.austin.trading.dto.internal.PortfolioRiskDecision;
import com.austin.trading.dto.internal.RankedCandidate;
import com.austin.trading.engine.PortfolioRiskEngine;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.PortfolioRiskDecisionRepository;
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
 * P0.5 Risk Layer — persistence + round-trip integration tests.
 */
@SpringBootTest
@ActiveProfiles("integration")
class PortfolioRiskServiceIntegrationTests {

    @Autowired PortfolioRiskService           service;
    @Autowired PortfolioRiskDecisionRepository riskRepo;

    private LocalDate uniqueDate;

    @BeforeEach
    void setUp() {
        uniqueDate = LocalDate.of(2005, 1, 1).plusDays(System.nanoTime() % 10_000);
    }

    private PositionEntity openPosition(String symbol, String reviewStatus,
                                         BigDecimal qty, BigDecimal avgCost) {
        PositionEntity p = new PositionEntity();
        p.setSymbol(symbol);
        p.setStatus("OPEN");
        p.setReviewStatus(reviewStatus);
        p.setQty(qty);
        p.setAvgCost(avgCost);
        p.setOpenedAt(LocalDateTime.now());
        return p;
    }

    private RankedCandidate candidate(String symbol, String theme) {
        return new RankedCandidate(uniqueDate, symbol,
                new BigDecimal("8.0"), new BigDecimal("7.5"), new BigDecimal("7.0"),
                new BigDecimal("7.8"), theme,
                false, true, null, "{}");
    }

    // ── Portfolio gate ─────────────────────────────────────────────────────

    @Test
    void evaluatePortfolioGate_noPositions_approved() {
        PortfolioRiskDecision d = service.evaluatePortfolioGate(List.of(), uniqueDate);

        assertThat(d.approved()).isTrue();
        assertThat(d.symbol()).isNull();

        var rows = riskRepo.findByTradingDate(uniqueDate);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isApproved()).isTrue();
        assertThat(rows.get(0).getSymbol()).isNull();
    }

    @Test
    void evaluatePortfolioGate_portfolioFull_blocked() {
        // MVP v2.6: max_open_positions 已從 3 調整為 4。
        // 4 non-STRONG positions → 等於 max → portfolio full → block
        List<PositionEntity> positions = List.of(
                openPosition("A", "HOLD", new BigDecimal("1000"), new BigDecimal("50")),
                openPosition("B", "WEAK", new BigDecimal("1000"), new BigDecimal("50")),
                openPosition("C", "HOLD", new BigDecimal("1000"), new BigDecimal("50")),
                openPosition("D", "HOLD", new BigDecimal("1000"), new BigDecimal("50"))
        );

        PortfolioRiskDecision d = service.evaluatePortfolioGate(positions, uniqueDate);

        assertThat(d.approved()).isFalse();
        assertThat(d.blockReason()).isEqualTo(PortfolioRiskEngine.BLOCK_PORTFOLIO_FULL);

        var rows = riskRepo.findByTradingDate(uniqueDate);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getBlockReason()).isEqualTo(PortfolioRiskEngine.BLOCK_PORTFOLIO_FULL);
    }

    // ── Per-candidate ──────────────────────────────────────────────────────

    @Test
    void evaluateCandidates_cleanPortfolio_allApproved() {
        List<RankedCandidate> candidates = List.of(
                candidate("PRS_A", "AI_THEME"),
                candidate("PRS_B", "EV_THEME")
        );

        List<PortfolioRiskDecision> results =
                service.evaluateCandidates(candidates, List.of(), uniqueDate);

        assertThat(results).hasSize(2);
        assertThat(results.stream().allMatch(PortfolioRiskDecision::approved)).isTrue();

        var rows = riskRepo.findByTradingDate(uniqueDate);
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().allMatch(r -> r.isApproved())).isTrue();
    }

    @Test
    void evaluateCandidates_alreadyHeld_blocked() {
        List<PositionEntity> positions = List.of(
                openPosition("PRS_C", "HOLD", new BigDecimal("1000"), new BigDecimal("80"))
        );
        List<RankedCandidate> candidates = List.of(candidate("PRS_C", "AI_THEME"));

        List<PortfolioRiskDecision> results =
                service.evaluateCandidates(candidates, positions, uniqueDate);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).approved()).isFalse();
        assertThat(results.get(0).blockReason()).isEqualTo(PortfolioRiskEngine.BLOCK_ALREADY_HELD);
    }

    @Test
    void getByDate_returnsAllPersistedRows() {
        service.evaluatePortfolioGate(List.of(), uniqueDate);
        service.evaluateCandidates(
                List.of(candidate("PRS_D", "AI_THEME")), List.of(), uniqueDate);

        List<PortfolioRiskDecision> all = service.getByDate(uniqueDate);
        assertThat(all).hasSize(2);  // 1 gate + 1 candidate
    }
}
