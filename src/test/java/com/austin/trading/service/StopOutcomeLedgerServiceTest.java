package com.austin.trading.service;

import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.StopOutcomeLedgerEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.StopOutcomeLedgerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StopOutcomeLedgerServiceTest {

    @Test
    void refreshClassifiesStopLossReboundAsWashoutWithoutProductionSideEffects() throws Exception {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        MarketIndexDailyRepository marketRepo = mock(MarketIndexDailyRepository.class);
        StopOutcomeLedgerRepository ledgerRepo = mock(StopOutcomeLedgerRepository.class);
        StopOutcomeLedgerService service = new StopOutcomeLedgerService(paperRepo, marketRepo, ledgerRepo, new ObjectMapper());

        LocalDate exitDate = LocalDate.of(2026, 5, 20);
        PaperTradeEntity trade = stoppedTrade(101L, "1582", exitDate, "STOP_LOSS", "100.00");
        when(paperRepo.findByStatusAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc(eq("CLOSED"), any()))
                .thenReturn(List.of(trade));
        when(ledgerRepo.findByPaperTradeId(101L)).thenReturn(Optional.empty());
        when(ledgerRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(marketRepo.findTradingDatesAfter(eq("t00"), eq(exitDate), any(Pageable.class)))
                .thenReturn(List.of(LocalDate.of(2026, 5, 21)))
                .thenReturn(List.of(LocalDate.of(2026, 5, 25)))
                .thenReturn(List.of(LocalDate.of(2026, 5, 27)))
                .thenReturn(List.of(LocalDate.of(2026, 6, 3)));
        when(marketRepo.findBySymbolAndTradingDate("1582", LocalDate.of(2026, 5, 21)))
                .thenReturn(Optional.of(bar("1582", LocalDate.of(2026, 5, 21), "102.00")));
        when(marketRepo.findBySymbolAndTradingDate("1582", LocalDate.of(2026, 5, 25)))
                .thenReturn(Optional.of(bar("1582", LocalDate.of(2026, 5, 25), "104.00")));
        when(marketRepo.findBySymbolAndTradingDate("1582", LocalDate.of(2026, 5, 27)))
                .thenReturn(Optional.of(bar("1582", LocalDate.of(2026, 5, 27), "106.50")));
        when(marketRepo.findBySymbolAndTradingDate("1582", LocalDate.of(2026, 6, 3)))
                .thenReturn(Optional.of(bar("1582", LocalDate.of(2026, 6, 3), "108.00")));

        StopOutcomeLedgerService.RefreshSummary summary = service.refresh(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 10));

        assertThat(summary.scanned()).isEqualTo(1);
        assertThat(summary.eligible()).isEqualTo(1);
        assertThat(summary.written()).isEqualTo(1);

        ArgumentCaptor<StopOutcomeLedgerEntity> captor = ArgumentCaptor.forClass(StopOutcomeLedgerEntity.class);
        verify(ledgerRepo).save(captor.capture());
        StopOutcomeLedgerEntity saved = captor.getValue();
        assertThat(saved.getPaperTradeId()).isEqualTo(101L);
        assertThat(saved.getSymbol()).isEqualTo("1582");
        assertThat(saved.getReturn5dAfterExit()).isEqualByComparingTo("6.5000");
        assertThat(saved.getMaxReturnAfterExit()).isEqualByComparingTo("8.0000");
        assertThat(saved.getOutcomeLabel()).isEqualTo("WASHOUT_REVERSAL");
        assertThat(saved.getEvidenceJson()).contains("stop_outcome_only_no_production_exit_change");
    }

    @Test
    void summaryDeclaresReadOnlyLearningLedger() {
        StopOutcomeLedgerRepository ledgerRepo = mock(StopOutcomeLedgerRepository.class);
        StopOutcomeLedgerService service = new StopOutcomeLedgerService(
                mock(PaperTradeRepository.class), mock(MarketIndexDailyRepository.class), ledgerRepo, new ObjectMapper());
        StopOutcomeLedgerEntity row = new StopOutcomeLedgerEntity();
        row.setPaperTradeId(1L);
        row.setSymbol("2330");
        row.setExitDate(LocalDate.now());
        row.setExitReason("TRAILING_STOP");
        row.setExitPrice(new BigDecimal("100"));
        row.setOutcomeLabel("TRUE_BREAKDOWN");
        when(ledgerRepo.findByExitDateGreaterThanEqualOrderByExitDateDescIdDesc(any()))
                .thenReturn(List.of(row));

        Map<String, Object> out = service.summary(30);

        assertThat(out.get("mode")).isEqualTo("READ_ONLY_LEARNING_LEDGER");
        assertThat(out.get("productionDecisionAllowed")).isEqualTo(false);
        assertThat(out.get("autoSellEnabled")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        Map<String, Long> byOutcome = (Map<String, Long>) out.get("byOutcomeLabel");
        assertThat(byOutcome).containsEntry("TRUE_BREAKDOWN", 1L);
    }

    private PaperTradeEntity stoppedTrade(Long id, String symbol, LocalDate exitDate, String reason, String exitPrice) throws Exception {
        PaperTradeEntity t = new PaperTradeEntity();
        setId(t, id);
        t.setTradeId("PT-" + id);
        t.setSymbol(symbol);
        t.setStockName("測試股");
        t.setEntryDate(exitDate.minusDays(5));
        t.setEntryPrice(new BigDecimal("105.00"));
        t.setExitDate(exitDate);
        t.setExitPrice(new BigDecimal(exitPrice));
        t.setExitReason(reason);
        t.setStatus("CLOSED");
        t.setThemeTag("LOW_ORBIT_SATELLITE");
        t.setStrategyType("SETUP");
        return t;
    }

    private void setId(PaperTradeEntity t, Long id) throws Exception {
        Field f = PaperTradeEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(t, id);
    }

    private MarketIndexDailyEntity bar(String symbol, LocalDate date, String close) {
        return new MarketIndexDailyEntity(symbol, date, null, null, null, new BigDecimal(close), 1000L);
    }
}
