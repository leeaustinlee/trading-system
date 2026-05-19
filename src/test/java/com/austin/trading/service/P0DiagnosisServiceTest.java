package com.austin.trading.service;

import com.austin.trading.engine.BacktestMetricsEngine;
import com.austin.trading.engine.PricePlanSanityEngine;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class P0DiagnosisServiceTest {

    @Test
    void pricePlanSanityFlagsInvalidPlanAndTp1Loss() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        PaperTradeEntity trade = trade("2330", bd("100"));
        trade.setStopLossPrice(bd("99.5"));
        trade.setTarget1Price(bd("99"));
        trade.setTarget2Price(bd("102"));
        trade.setEntryRrRatio(bd("-1"));
        trade.setExitReason("TP1_HIT");
        trade.setPnlPct(bd("-0.5"));
        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any())).thenReturn(List.of(trade));

        P0BacktestDiagnosisService service = new P0BacktestDiagnosisService(
                paperRepo, forwardRepo, candidateRepo, mock(MarketIndexDailyRepository.class),
                new PricePlanSanityEngine(null), new ObjectMapper());

        Map<String, Object> out = service.pricePlanSanity(60);
        assertThat(out.get("totalTrades")).isEqualTo(1);
        assertThat(out.get("flaggedTrades")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        @SuppressWarnings("unchecked")
        List<String> violations = (List<String>) rows.get(0).get("violations");
        assertThat(violations).contains("TP1_NOT_ABOVE_ENTRY", "TP1_HIT_NON_POSITIVE_PNL", "ENTRY_RR_RATIO_NON_POSITIVE");
    }

    @Test
    void themePropagationSeparatesTraceLossMappingGapAndTrueOther() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        LocalDate d = LocalDate.now().minusDays(2);
        PaperTradeEntity t1 = trade("2330", bd("100")); t1.setThemeTag("UNKNOWN"); t1.setEntryDate(d);
        PaperTradeEntity t2 = trade("2383", bd("100")); t2.setThemeTag("UNKNOWN"); t2.setEntryDate(d);
        PaperTradeEntity t3 = trade("9999", bd("100")); t3.setThemeTag("OTHER"); t3.setEntryDate(d);
        CandidateForwardTrackingEntity f1 = forward(d, "2330", "半導體", "ASIC 半導體");
        CandidateStockEntity c2 = candidate(d, "2383", "PCB", "AI PCB CCL");
        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any())).thenReturn(List.of(t1, t2, t3));
        when(forwardRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of(f1));
        when(candidateRepo.findByTradingDateBetweenOrderByTradingDateDescScoreDesc(any(), any())).thenReturn(List.of(c2));

        P0BacktestDiagnosisService service = new P0BacktestDiagnosisService(
                paperRepo, forwardRepo, candidateRepo, mock(MarketIndexDailyRepository.class),
                new PricePlanSanityEngine(null), new ObjectMapper());

        Map<String, Object> out = service.themePropagation(60);
        assertThat(out.get("tradeTraceLoss")).isEqualTo(1L);
        assertThat(out.get("candidateMappingGap")).isEqualTo(1L);
        assertThat(out.get("trueOtherUnmapped")).isEqualTo(1L);
    }

    @Test
    void exitRuleComparisonReturnsDataGapWhenNoDailyBars() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        MarketIndexDailyRepository marketRepo = mock(MarketIndexDailyRepository.class);
        PaperTradeEntity t = trade("2330", bd("100"));
        t.setEntryDate(LocalDate.now().minusDays(10));
        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any())).thenReturn(List.of(t));
        when(marketRepo.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(eq("2330"), any(), any())).thenReturn(List.of());
        P0BacktestDiagnosisService service = new P0BacktestDiagnosisService(
                paperRepo, mock(CandidateForwardTrackingRepository.class), mock(CandidateStockRepository.class), marketRepo,
                new PricePlanSanityEngine(null), new ObjectMapper());

        Map<String, Object> out = service.exitRuleComparison(60);
        assertThat(out.get("status")).isEqualTo("DATA_GAP");
        @SuppressWarnings("unchecked")
        List<String> gaps = (List<String>) out.get("dataGaps");
        assertThat(gaps).anyMatch(s -> s.contains("no daily bars"));
    }

    private PaperTradeEntity trade(String symbol, BigDecimal entry) {
        PaperTradeEntity t = new PaperTradeEntity();
        t.setTradeId("T-" + symbol + "-" + System.nanoTime());
        t.setEntryDate(LocalDate.now().minusDays(5));
        t.setSymbol(symbol);
        t.setEntryPrice(entry);
        t.setStrategyType("SETUP");
        t.setStatus("CLOSED");
        return t;
    }

    private CandidateForwardTrackingEntity forward(LocalDate d, String symbol, String theme, String reason) {
        CandidateForwardTrackingEntity f = new CandidateForwardTrackingEntity();
        f.setTradingDate(d); f.setStockId(symbol); f.setThemeTag(theme); f.setThemeReason(reason); return f;
    }

    private CandidateStockEntity candidate(LocalDate d, String symbol, String theme, String reason) {
        CandidateStockEntity c = new CandidateStockEntity();
        c.setTradingDate(d); c.setSymbol(symbol); c.setThemeTag(theme); c.setReason(reason); return c;
    }

    private BigDecimal bd(String v) { return new BigDecimal(v); }
}
