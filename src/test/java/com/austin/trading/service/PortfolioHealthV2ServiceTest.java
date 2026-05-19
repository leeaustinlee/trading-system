package com.austin.trading.service;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.engine.PositionHealthEngine;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.PositionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PortfolioHealthV2ServiceTest {

    @Test
    void healthV2MapsReduceReviewAndNeverAutoSell() {
        PositionRepository positionRepo = mock(PositionRepository.class);
        DailyTechnicalService tech = mock(DailyTechnicalService.class);
        PositionEntity p = new PositionEntity();
        p.setSymbol("2330");
        p.setStockName("台積電");
        p.setStatus("OPEN");
        p.setAvgCost(bd("100"));
        p.setStopLossPrice(bd("92"));
        p.setTrailingStopPrice(bd("95"));
        p.setOpenedAt(LocalDateTime.now().minusDays(5));
        when(positionRepo.findByStatus("OPEN")).thenReturn(List.of(p));
        when(tech.snapshot(eq("2330"), any())).thenReturn(new DailyTechnicalService.TechnicalSnapshot(
                bd("102"), bd("101"), bd("98"), bd("103"), bd("96"), bd("110"), bd("3"), bd("2.0"), bd("-1"), bd("2"), List.of()));
        when(tech.snapshot(eq("t00"), any())).thenReturn(new DailyTechnicalService.TechnicalSnapshot(
                null, null, null, null, null, null, null, null, bd("1"), bd("2"), List.of()));

        PortfolioHealthV2Service service = new PortfolioHealthV2Service(positionRepo, tech, null, new PositionHealthEngine());
        Map<String, Object> out = service.healthV2();

        assertThat(out.get("mode")).isEqualTo("SHADOW_MANUAL_CONFIRM_ONLY");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("positions");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("actionTier")).isIn("REDUCE_REVIEW", "EXIT_REVIEW", "HARD_EXIT_ALERT");
        assertThat(rows.get(0).get("autoSellEnabled")).isEqualTo(false);
    }

    @Test
    void healthV2DataGapsSummarizesAffectedPositionsWithoutAutoSell() {
        PositionRepository positionRepo = mock(PositionRepository.class);
        DailyTechnicalService tech = mock(DailyTechnicalService.class);
        PositionEntity p = new PositionEntity();
        p.setSymbol("2383");
        p.setStockName("台光電");
        p.setStatus("OPEN");
        p.setAvgCost(bd("100"));
        when(positionRepo.findByStatus("OPEN")).thenReturn(List.of(p));
        when(tech.snapshot(eq("2383"), any())).thenReturn(DailyTechnicalService.TechnicalSnapshot.empty(List.of("DATA_GAP: daily bars missing")));
        when(tech.snapshot(eq("t00"), any())).thenReturn(DailyTechnicalService.TechnicalSnapshot.empty(List.of("DATA_GAP: benchmark daily data unavailable")));

        PortfolioHealthV2Service service = new PortfolioHealthV2Service(positionRepo, tech, null, new PositionHealthEngine());
        Map<String, Object> out = service.healthV2DataGaps();

        assertThat(out.get("autoSellEnabled")).isEqualTo(false);
        assertThat(out.get("positionsWithDataGaps")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Integer> byGap = (Map<String, Integer>) out.get("byDataGap");
        assertThat(byGap.keySet()).anyMatch(k -> k.contains("daily bars"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> affected = (List<Map<String, Object>>) out.get("affectedPositions");
        assertThat(affected.get(0).get("recommendedDataFix").toString()).contains("日線");
    }

    private BigDecimal bd(String v) { return new BigDecimal(v); }
}
