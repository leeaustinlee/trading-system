package com.austin.trading.service;

import com.austin.trading.dto.internal.TradeAttributionOutput;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.TradeAttributionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1.2 Trade Attribution Layer — persistence + round-trip integration tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class TradeAttributionServiceIntegrationTests {

    @Autowired TradeAttributionService   service;
    @Autowired PositionRepository        positionRepo;
    @Autowired TradeAttributionRepository attributionRepo;

    // ── computeForPosition ────────────────────────────────────────────────────

    @Test
    void closedPosition_producesAndPersistsAttribution() {
        PositionEntity pos = savedClosedPos("ATTR_A", bd(100), bd(108));

        Optional<TradeAttributionOutput> out = service.computeForPosition(pos);

        assertThat(out).isPresent();
        assertThat(out.get().symbol()).isEqualTo("ATTR_A");
        assertThat(out.get().pnlPct()).isEqualByComparingTo(bd(8.0));
        assertThat(attributionRepo.findByPositionId(pos.getId())).isPresent();
    }

    @Test
    void openPosition_isSkipped() {
        PositionEntity pos = new PositionEntity();
        pos.setSymbol("OPEN_POS");
        pos.setStatus("OPEN");
        pos.setAvgCost(bd(100));
        pos.setOpenedAt(LocalDateTime.now());

        Optional<TradeAttributionOutput> out = service.computeForPosition(pos);
        assertThat(out).isEmpty();
    }

    @Test
    void duplicateCall_isIdempotent() {
        PositionEntity pos = savedClosedPos("ATTR_B", bd(50), bd(55));
        service.computeForPosition(pos);
        service.computeForPosition(pos); // second call should be a no-op

        // Exactly one attribution row for this position
        assertThat(attributionRepo.findByPositionId(pos.getId())).isPresent();
        assertThat(attributionRepo.findBySymbolOrderByEntryDateDesc("ATTR_B")
                .stream().filter(e -> e.getPositionId().equals(pos.getId())).toList())
                .hasSize(1);
    }

    @Test
    void backfillAll_processesAllClosedPositions() {
        PositionEntity p1 = savedClosedPos("BACK_A", bd(200), bd(210));
        PositionEntity p2 = savedClosedPos("BACK_B", bd(100), bd(95));

        int count = service.backfillAll(List.of(p1, p2));

        assertThat(count).isEqualTo(2);
        assertThat(attributionRepo.findByPositionId(p1.getId())).isPresent();
        assertThat(attributionRepo.findByPositionId(p2.getId())).isPresent();
    }

    @Test
    void findForPosition_returnsAttribution() {
        PositionEntity pos = savedClosedPos("FIND_A", bd(75), bd(80));
        service.computeForPosition(pos);

        Optional<TradeAttributionOutput> found = service.findForPosition(pos.getId());
        assertThat(found).isPresent();
        assertThat(found.get().symbol()).isEqualTo("FIND_A");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private PositionEntity savedClosedPos(String symbol, BigDecimal entry, BigDecimal exit) {
        PositionEntity p = new PositionEntity();
        p.setSymbol(symbol);
        p.setStatus("CLOSED");
        p.setAvgCost(entry);
        p.setClosePrice(exit);
        p.setQty(BigDecimal.valueOf(1000));
        p.setOpenedAt(LocalDateTime.now().minusDays(5));
        p.setClosedAt(LocalDateTime.now());
        return positionRepo.save(p);
    }

    private static BigDecimal bd(double v) { return BigDecimal.valueOf(v); }
}
