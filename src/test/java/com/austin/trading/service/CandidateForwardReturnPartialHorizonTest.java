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

class CandidateForwardReturnPartialHorizonTest {

    @Test
    void computesCompletedHorizonsEvenWhenT10IsNotReady() {
        CandidateForwardTrackingRepository candidateRepo = mock(CandidateForwardTrackingRepository.class);
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        MarketIndexDailyRepository marketRepo = mock(MarketIndexDailyRepository.class);
        LocalDate entryDate = LocalDate.now().minusDays(7);
        CandidateForwardTrackingEntity row = new CandidateForwardTrackingEntity();
        row.setTradingDate(entryDate);
        row.setStockId("2330");
        row.setEntryPriceAtDecision(new BigDecimal("100"));

        List<LocalDate> futureDates = new ArrayList<>();
        for (int i = 1; i <= 10; i++) futureDates.add(entryDate.plusDays(i));

        when(candidateRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of(row));
        when(marketRepo.findTradingDatesAfter(eq("t00"), eq(entryDate), any())).thenReturn(futureDates);
        when(marketRepo.findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(eq("2330"), eq(entryDate), eq(futureDates.get(4))))
                .thenReturn(stockBarsThroughT5(entryDate));
        when(marketRepo.findBySymbolAndTradingDate("t00", entryDate))
                .thenReturn(Optional.of(bar("t00", entryDate, "100")));
        when(marketRepo.findBySymbolAndTradingDate("t00", futureDates.get(4)))
                .thenReturn(Optional.of(bar("t00", futureDates.get(4), "102")));

        var result = new CandidateForwardReturnBackfillService(candidateRepo, paperRepo, marketRepo)
                .backfillReturns(60);

        assertThat(result.get("processedRows")).isEqualTo(1);
        assertThat(result.get("updatedRows")).isEqualTo(1);
        assertThat(result.get("dataGapRows")).isEqualTo(1);
        assertThat(result.get("benchmarkHorizon").toString()).contains("T5");
        assertThat(result.get("dataGaps").toString()).contains("T10");
        assertThat(row.getT1CloseReturnPct()).isEqualByComparingTo("1.0000");
        assertThat(row.getT3CloseReturnPct()).isEqualByComparingTo("3.0000");
        assertThat(row.getT5CloseReturnPct()).isEqualByComparingTo("5.0000");
        assertThat(row.getT10CloseReturnPct()).isNull();
        assertThat(row.getBenchmarkReturnPct()).isEqualByComparingTo("2.0000");
        assertThat(row.getRelativeReturnPct()).isEqualByComparingTo("3.0000");
        verify(candidateRepo).save(row);
    }

    private List<MarketIndexDailyEntity> stockBarsThroughT5(LocalDate entryDate) {
        List<MarketIndexDailyEntity> bars = new ArrayList<>();
        bars.add(bar("2330", entryDate, "100"));
        for (int i = 1; i <= 5; i++) {
            bars.add(bar("2330", entryDate.plusDays(i), String.valueOf(100 + i)));
        }
        return bars;
    }

    private MarketIndexDailyEntity bar(String symbol, LocalDate date, String close) {
        return new MarketIndexDailyEntity(symbol, date, new BigDecimal(close), new BigDecimal(close),
                new BigDecimal(close), new BigDecimal(close), 1000L);
    }
}
