package com.austin.trading.service;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PaperTradeRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CandidateForwardReturnBackfillServiceTest {

    @Test
    void backfillReturnsFromMarketIndexDailyAndPersistsMaxDrawdown() {
        CandidateForwardTrackingRepository candidateRepo = mock(CandidateForwardTrackingRepository.class);
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        MarketIndexDailyRepository marketRepo = mock(MarketIndexDailyRepository.class);
        LocalDate entryDate = LocalDate.now().minusDays(20);
        CandidateForwardTrackingEntity row = new CandidateForwardTrackingEntity();
        row.setTradingDate(entryDate);
        row.setStockId("2330");
        row.setStockName("台積電");
        row.setFinalDecision("ENTER");
        row.setEntryPriceAtDecision(new BigDecimal("100"));

        List<LocalDate> futureDates = new ArrayList<>();
        for (int i = 1; i <= 10; i++) futureDates.add(entryDate.plusDays(i));

        when(candidateRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of(row));
        when(marketRepo.findTradingDatesAfter(eq("t00"), eq(entryDate), any())).thenReturn(futureDates);
        when(marketRepo.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(eq("2330"), eq(entryDate), eq(futureDates.get(9))))
                .thenReturn(stockBars(entryDate));
        when(marketRepo.findBySymbolAndTradingDate("t00", entryDate))
                .thenReturn(Optional.of(bar("t00", entryDate, "100", "100", "100")));
        when(marketRepo.findBySymbolAndTradingDate("t00", futureDates.get(9)))
                .thenReturn(Optional.of(bar("t00", futureDates.get(9), "105", "105", "105")));

        var result = new CandidateForwardReturnBackfillService(candidateRepo, paperRepo, marketRepo)
                .backfillReturns(60);

        assertThat(result.get("processedRows")).isEqualTo(1);
        assertThat(result.get("updatedRows")).isEqualTo(1);
        assertThat(row.getT1CloseReturnPct()).isEqualByComparingTo("1.0000");
        assertThat(row.getT5CloseReturnPct()).isEqualByComparingTo("5.0000");
        assertThat(row.getT10CloseReturnPct()).isEqualByComparingTo("10.0000");
        assertThat(row.getMfePct()).isEqualByComparingTo("12.0000");
        assertThat(row.getMaePct()).isEqualByComparingTo("-3.0000");
        assertThat(row.getMaxDrawdownPct()).isEqualByComparingTo("-5.3571");
        assertThat(row.getBenchmarkReturnPct()).isEqualByComparingTo("5.0000");
        assertThat(row.getRelativeReturnPct()).isEqualByComparingTo("5.0000");
        verify(candidateRepo).save(row);
    }

    @Test
    void dataGapDoesNotInventReturns() {
        CandidateForwardTrackingRepository candidateRepo = mock(CandidateForwardTrackingRepository.class);
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        MarketIndexDailyRepository marketRepo = mock(MarketIndexDailyRepository.class);
        CandidateForwardTrackingEntity row = new CandidateForwardTrackingEntity();
        row.setTradingDate(LocalDate.now().minusDays(5));
        row.setStockId("2330");
        row.setEntryPriceAtDecision(new BigDecimal("100"));
        when(candidateRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of(row));
        when(marketRepo.findTradingDatesAfter(anyString(), any(), any())).thenReturn(List.of(LocalDate.now()));

        var result = new CandidateForwardReturnBackfillService(candidateRepo, paperRepo, marketRepo)
                .backfillReturns(60);

        assertThat(result.get("dataGapRows")).isEqualTo(1);
        assertThat(result.get("dataGaps").toString()).contains("DATA_GAP");
        assertThat(row.getT5CloseReturnPct()).isNull();
        verify(candidateRepo, never()).save(row);
    }

    private List<MarketIndexDailyEntity> stockBars(LocalDate entryDate) {
        List<MarketIndexDailyEntity> bars = new ArrayList<>();
        bars.add(bar("2330", entryDate, "100", "100", "100"));
        for (int i = 1; i <= 10; i++) {
            String close = String.valueOf(100 + i);
            String high = i == 10 ? "112" : close;
            String low = i == 6 ? "106" : close;
            if (i == 2) low = "97";
            if (i == 10) low = "106";
            bars.add(bar("2330", entryDate.plusDays(i), close, high, low));
        }
        return bars;
    }

    private MarketIndexDailyEntity bar(String symbol, LocalDate date, String close, String high, String low) {
        return new MarketIndexDailyEntity(symbol, date, new BigDecimal(close), new BigDecimal(high),
                new BigDecimal(low), new BigDecimal(close), 1000L);
    }
}
