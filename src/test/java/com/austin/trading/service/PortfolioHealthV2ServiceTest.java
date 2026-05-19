package com.austin.trading.service;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.client.dto.StockQuote;
import com.austin.trading.engine.PositionHealthEngine;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    void healthV2UsesThemeMappingAndCandidateChipPayload() {
        PositionRepository positionRepo = mock(PositionRepository.class);
        DailyTechnicalService tech = mock(DailyTechnicalService.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        StockThemeMappingRepository mappingRepo = mock(StockThemeMappingRepository.class);
        TwseMisClient quoteClient = mock(TwseMisClient.class);
        PositionEntity p = new PositionEntity();
        p.setSymbol("2330");
        p.setStockName("台積電");
        p.setStatus("OPEN");
        p.setAvgCost(bd("100"));
        when(positionRepo.findByStatus("OPEN")).thenReturn(List.of(p));
        when(tech.snapshot(eq("2330"), any())).thenReturn(new DailyTechnicalService.TechnicalSnapshot(
                bd("100"), bd("99"), bd("98"), bd("99"), bd("95"), bd("105"), bd("2"), bd("1.1"), bd("5"), bd("6"), List.of()));
        when(tech.snapshot(eq("t00"), any())).thenReturn(new DailyTechnicalService.TechnicalSnapshot(
                null, null, null, null, null, null, null, bd("1.0"), bd("1"), bd("2"), List.of()));
        StockThemeMappingEntity mapping = new StockThemeMappingEntity();
        mapping.setSymbol("2330"); mapping.setThemeTag("SEMICONDUCTOR"); mapping.setThemeCategory("SEMICONDUCTOR"); mapping.setIsActive(true);
        when(mappingRepo.findBySymbolAndIsActiveTrue("2330")).thenReturn(List.of(mapping));
        CandidateStockEntity candidate = new CandidateStockEntity();
        candidate.setTradingDate(LocalDate.now().minusDays(1));
        candidate.setSymbol("2330");
        candidate.setPayloadJson("{\"foreign_and_trust_buy\":true,\"total_institutional_net\":1000,\"foreign_net\":700,\"invest_trust_net\":300}");
        when(candidateRepo.findTopBySymbolOrderByTradingDateDesc("2330")).thenReturn(Optional.of(candidate));
        when(quoteClient.getQuotesWithOtcFallback(List.of("2330"))).thenReturn(List.of(
                new StockQuote("2330", "台積電", "tse", 101.0, 100.0, 100.0, 102.0, 99.0, 101.0, 101.5, 1000L, "20260519", "10:00:00", true)));

        PortfolioHealthV2Service service = new PortfolioHealthV2Service(positionRepo, tech, quoteClient, new PositionHealthEngine(), candidateRepo, mappingRepo, new ObjectMapper());
        Map<String, Object> out = service.healthV2();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("positions");

        assertThat(rows.get(0).get("reasons").toString()).contains("mainstream_theme");
        assertThat(rows.get(0).get("chipStatus")).isEqualTo("BULLISH");
        assertThat(rows.get(0).get("dataGaps").toString()).doesNotContain("chip data missing").doesNotContain("mainstream theme mapping missing");
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
