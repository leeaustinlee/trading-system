package com.austin.trading.service.regime;

import com.austin.trading.client.TwseHistoryClient;
import com.austin.trading.client.TwseHistoryClient.DailyBar;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.service.ScoreConfigService;
import org.junit.jupiter.api.Test;

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
                client, base, paperRepo, forwardRepo, candidateRepo, cfg);

        var result = service.backfillSymbols(30, "2330,2303");

        assertThat(result.get("requestedSymbols")).asList().containsExactly("2330", "2303");
        assertThat(result.get("resolvedSymbols")).asList().containsExactly("2330", "2303");
        assertThat((Integer) result.get("upsertedRows")).isGreaterThan(0);
        assertThat(result.get("skippedSymbols")).asList().contains("2303");
        assertThat(result.get("dataGaps").toString()).contains("2303").contains("DATA_GAP");
        verify(client, atLeastOnce()).fetchTaiexMonth(any(YearMonth.class));
        verify(client, atLeastOnce()).fetchStockMonth(eq("2330"), any(YearMonth.class));
    }

    private DailyBar bar(String symbol, LocalDate date) {
        return new DailyBar(symbol, date, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 100L);
    }
}
