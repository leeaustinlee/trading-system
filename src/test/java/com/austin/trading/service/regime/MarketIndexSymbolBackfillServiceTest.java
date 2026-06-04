package com.austin.trading.service.regime;

import com.austin.trading.client.TwseHistoryClient;
import com.austin.trading.client.TwseHistoryClient.DailyBar;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MarketIndexSymbolBackfillServiceTest {

    @Test
    void backfillSymbolsAlwaysIncludesTaiexAndRecordsSymbolDataGaps() {
        TwseHistoryClient client = mock(TwseHistoryClient.class);
        MarketIndexDailyRepository marketRepo = mock(MarketIndexDailyRepository.class);
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        ScoreConfigService cfg = mock(ScoreConfigService.class);
        when(cfg.getInt(eq(MarketIndexBackfillService.CFG_THROTTLE_MS), anyInt())).thenReturn(0);
        when(marketRepo.findBySymbolAndTradingDate(anyString(), any(LocalDate.class))).thenReturn(Optional.empty());
        when(client.fetchTaiexMonth(any(YearMonth.class))).thenAnswer(inv -> List.of(bar("t00", LocalDate.now().minusDays(3))));
        when(client.fetchStockMonth(eq("2330"), any(YearMonth.class))).thenAnswer(inv -> List.of(bar("2330", LocalDate.now().minusDays(3))));
        when(client.fetchStockMonth(eq("2303"), any(YearMonth.class))).thenReturn(List.of());

        MarketIndexBackfillService base = new MarketIndexBackfillService(client, marketRepo, cfg);
        MarketIndexSymbolBackfillService service = new MarketIndexSymbolBackfillService(
                client, base, paperRepo, forwardRepo, candidateRepo, mock(PositionRepository.class), cfg);

        var result = service.backfillSymbols(30, "2330,2303");

        assertThat(result.get("requestedSymbols")).asList().containsExactly("2330", "2303");
        assertThat(result.get("resolvedSymbols")).asList().containsExactly("2330", "2303");
        assertThat((Integer) result.get("upsertedRows")).isGreaterThan(0);
        assertThat(result.get("skippedSymbols")).asList().contains("2303");
        assertThat(result.get("dataGaps").toString()).contains("2303").contains("DATA_GAP");
        verify(client, atLeastOnce()).fetchTaiexMonth(any(YearMonth.class));
        verify(client, atLeastOnce()).fetchStockMonth(eq("2330"), any(YearMonth.class));
    }

    @Test
    void autoCollectsPaperTradeAndCandidateForwardSymbolsWithLimit() {
        TwseHistoryClient client = mock(TwseHistoryClient.class);
        MarketIndexBackfillService base = mock(MarketIndexBackfillService.class);
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        ScoreConfigService cfg = mock(ScoreConfigService.class);
        when(cfg.getInt(eq(MarketIndexBackfillService.CFG_THROTTLE_MS), anyInt())).thenReturn(0);
        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any()))
                .thenReturn(List.of(paper("2330")));
        when(forwardRepo.findByTradingDateBetween(any(), any()))
                .thenReturn(List.of(forward("2303"), forward("2454")));
        when(client.fetchTaiexMonth(any(YearMonth.class))).thenReturn(List.of(bar("t00", LocalDate.now().minusDays(3))));
        when(client.fetchStockMonth(anyString(), any(YearMonth.class))).thenAnswer(inv ->
                List.of(bar(inv.getArgument(0), LocalDate.now().minusDays(3))));
        when(base.upsertBars(anyList(), any(), any())).thenReturn(1);

        MarketIndexSymbolBackfillService service = new MarketIndexSymbolBackfillService(
                client, base, paperRepo, forwardRepo, candidateRepo, mock(PositionRepository.class), cfg);

        var result = service.backfillSymbols(30, null, true, true, 2);

        assertThat(result.get("resolvedSymbols")).asList().containsExactly("2330", "2303");
        assertThat(result.get("symbolStats").toString()).contains("2330").contains("2303");
    }


    @Test
    void autoCollectsOpenPositionSymbolsForDailyPortfolioRefresh() {
        TwseHistoryClient client = mock(TwseHistoryClient.class);
        MarketIndexBackfillService base = mock(MarketIndexBackfillService.class);
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        PositionRepository positionRepo = mock(PositionRepository.class);
        ScoreConfigService cfg = mock(ScoreConfigService.class);
        when(cfg.getInt(eq(MarketIndexBackfillService.CFG_THROTTLE_MS), anyInt())).thenReturn(0);
        when(positionRepo.findByStatus("OPEN")).thenReturn(List.of(position("4938")));
        when(client.fetchTaiexMonth(any(YearMonth.class))).thenReturn(List.of(bar("t00", LocalDate.now().minusDays(3))));
        when(client.fetchStockMonth(anyString(), any(YearMonth.class))).thenAnswer(inv ->
                List.of(bar(inv.getArgument(0), LocalDate.now().minusDays(3))));
        when(base.upsertBars(anyList(), any(), any())).thenReturn(1);

        MarketIndexSymbolBackfillService service = new MarketIndexSymbolBackfillService(
                client, base, paperRepo, forwardRepo, candidateRepo, positionRepo, cfg);

        var result = service.backfillSymbols(30, null, false, false, 10);

        assertThat(result.get("resolvedSymbols")).asList().containsExactly("4938");
        verify(client, atLeastOnce()).fetchStockMonth(eq("4938"), any(YearMonth.class));
    }

    @Test
    void benchmarkDataGapDoesNotFailStockBackfill() {
        TwseHistoryClient client = mock(TwseHistoryClient.class);
        MarketIndexBackfillService base = mock(MarketIndexBackfillService.class);
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        ScoreConfigService cfg = mock(ScoreConfigService.class);
        when(cfg.getInt(eq(MarketIndexBackfillService.CFG_THROTTLE_MS), anyInt())).thenReturn(0);
        when(client.fetchTaiexMonth(any(YearMonth.class))).thenReturn(List.of());
        when(client.fetchStockMonth(eq("2330"), any(YearMonth.class))).thenAnswer(inv ->
                List.of(bar("2330", LocalDate.now().minusDays(3))));
        when(base.upsertBars(anyList(), any(), any())).thenReturn(1);

        MarketIndexSymbolBackfillService service = new MarketIndexSymbolBackfillService(
                client, base, paperRepo, forwardRepo, candidateRepo, mock(PositionRepository.class), cfg);

        var result = service.backfillSymbols(30, "2330", true, true, 50);

        assertThat(result.get("benchmarkDataGap")).isEqualTo(true);
        assertThat((Integer) result.get("upsertedRows")).isGreaterThan(0);
        assertThat(result.get("dataGaps").toString()).contains("BENCHMARK_DATA_GAP");
    }

    @Test
    void coverageReportsInsufficientBarsReadOnly() {
        TwseHistoryClient client = mock(TwseHistoryClient.class);
        MarketIndexBackfillService base = mock(MarketIndexBackfillService.class);
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        CandidateStockRepository candidateRepo = mock(CandidateStockRepository.class);
        ScoreConfigService cfg = mock(ScoreConfigService.class);
        when(base.findBars(eq("t00"), any(), any())).thenReturn(List.of(
                new MarketIndexDailyEntity("t00", LocalDate.now().minusDays(2), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 1L)));
        when(base.findBars(eq("2330"), any(), any())).thenReturn(List.of(
                new MarketIndexDailyEntity("2330", LocalDate.now().minusDays(2), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 1L)));

        MarketIndexSymbolBackfillService service = new MarketIndexSymbolBackfillService(
                client, base, paperRepo, forwardRepo, candidateRepo, mock(PositionRepository.class), cfg);

        var result = service.coverage(30, "2330", false, false, 10);

        assertThat(result.get("mode")).isEqualTo("READ_ONLY_COVERAGE_ONLY");
        assertThat(result.get("symbolsWithInsufficientBars")).isEqualTo(2);
        assertThat(result.get("symbolStats").toString()).contains("BACKFILL_DAILY_BARS");
        verify(base, never()).upsertBars(anyList(), any(), any());
    }

    @Test
    void upsertBarsSkipsUnchangedExistingDailyRows() {
        TwseHistoryClient client = mock(TwseHistoryClient.class);
        MarketIndexDailyRepository marketRepo = mock(MarketIndexDailyRepository.class);
        ScoreConfigService cfg = mock(ScoreConfigService.class);
        LocalDate date = LocalDate.now().minusDays(3);
        MarketIndexDailyEntity existing = new MarketIndexDailyEntity(
                "2330", date, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 100L);
        ReflectionTestUtils.setField(existing, "id", 1L);
        when(marketRepo.findBySymbolAndTradingDate("2330", date)).thenReturn(Optional.of(existing));

        MarketIndexBackfillService base = new MarketIndexBackfillService(client, marketRepo, cfg);

        int upserted = base.upsertBars(List.of(bar("2330", date)), date.minusDays(1), date.plusDays(1));

        assertThat(upserted).isZero();
        verify(marketRepo, never()).save(any());
    }

    private DailyBar bar(String symbol, LocalDate date) {
        return new DailyBar(symbol, date, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 100L);
    }

    private PaperTradeEntity paper(String symbol) {
        PaperTradeEntity entity = new PaperTradeEntity();
        entity.setEntryDate(LocalDate.now().minusDays(5));
        entity.setSymbol(symbol);
        return entity;
    }

    private PositionEntity position(String symbol) {
        PositionEntity entity = new PositionEntity();
        entity.setSymbol(symbol);
        entity.setStatus("OPEN");
        return entity;
    }

    private CandidateForwardTrackingEntity forward(String symbol) {
        CandidateForwardTrackingEntity entity = new CandidateForwardTrackingEntity();
        entity.setTradingDate(LocalDate.now().minusDays(5));
        entity.setStockId(symbol);
        return entity;
    }
}
