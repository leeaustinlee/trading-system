package com.austin.trading.service;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.entity.RrShadowValidationEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.austin.trading.repository.RrShadowValidationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RrShadowValidationServiceTest {

    @Test
    void backfillPersistsShadowRowsAndIsIdempotentByPaperTradeId() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        Map<Long, RrShadowValidationEntity> rows = new LinkedHashMap<>();
        RrShadowValidationRepository validationRepo = validationRepository(rows);
        PaperTradeEntity trade = trade(1L, "2330", "100", "95", "102");
        trade.setReturn5d(new BigDecimal("-1.2"));

        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any())).thenReturn(List.of(trade));
        when(forwardRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of());

        RrShadowValidationService service = new RrShadowValidationService(
                paperRepo, forwardRepo, validationRepo, new RiskRewardShadowGateService());

        service.backfill(60);
        service.backfill(60);

        assertThat(rows).hasSize(1);
        RrShadowValidationService.Summary summary = service.summary(60);
        assertThat(summary.totalRows()).isEqualTo(1);
        assertThat(summary.wouldBlockCount()).isEqualTo(1);
        assertThat(summary.avoidedLoserCount()).isEqualTo(1);
        assertThat(summary.missedWinnerCount()).isZero();
    }

    @Test
    void summaryKeepsReturnDataGapsSeparateAndDetectsMissedWinner() {
        PaperTradeRepository paperRepo = mock(PaperTradeRepository.class);
        CandidateForwardTrackingRepository forwardRepo = mock(CandidateForwardTrackingRepository.class);
        RrShadowValidationRepository validationRepo = validationRepository(new LinkedHashMap<>());
        PaperTradeEntity trade = trade(2L, "2454", "100", "95", "102");
        CandidateForwardTrackingEntity forward = new CandidateForwardTrackingEntity();
        forward.setTradingDate(trade.getEntryDate());
        forward.setStockId("2454");
        forward.setT5CloseReturnPct(new BigDecimal("4.2"));

        when(paperRepo.findByEntryDateBetweenOrderByEntryDateAscIdAsc(any(), any())).thenReturn(List.of(trade));
        when(forwardRepo.findByTradingDateBetween(any(), any())).thenReturn(List.of(forward));

        RrShadowValidationService service = new RrShadowValidationService(
                paperRepo, forwardRepo, validationRepo, new RiskRewardShadowGateService());

        service.backfill(60);
        RrShadowValidationService.Summary summary = service.summary(60);

        assertThat(summary.wouldBlockCount()).isEqualTo(1);
        assertThat(summary.blockedAvgReturnT5()).isEqualByComparingTo("4.2000");
        assertThat(summary.missedWinnerCount()).isEqualTo(1);
        assertThat(summary.dataGaps()).containsEntry("T1", 1).containsEntry("T3", 1).containsEntry("T10", 1);
        assertThat(summary.blockedReturnCoveragePct()).isEqualByComparingTo("100.00");
    }

    private PaperTradeEntity trade(Long id, String symbol, String entry, String stop, String target1) {
        PaperTradeEntity trade = new PaperTradeEntity();
        ReflectionTestUtils.setField(trade, "id", id);
        trade.setEntryDate(LocalDate.now().minusDays(5));
        trade.setSymbol(symbol);
        trade.setStatus("CLOSED");
        trade.setStrategyType("SETUP");
        trade.setEntryPrice(new BigDecimal(entry));
        trade.setStopLossPrice(new BigDecimal(stop));
        trade.setTarget1Price(new BigDecimal(target1));
        return trade;
    }

    private RrShadowValidationRepository validationRepository(Map<Long, RrShadowValidationEntity> rows) {
        RrShadowValidationRepository repository = mock(RrShadowValidationRepository.class);
        when(repository.findByPaperTradeId(any())).thenAnswer(inv -> Optional.ofNullable(rows.get(inv.getArgument(0))));
        when(repository.save(any())).thenAnswer(inv -> {
            RrShadowValidationEntity entity = inv.getArgument(0);
            if (entity.getId() == null) ReflectionTestUtils.setField(entity, "id", (long) rows.size() + 1);
            rows.put(entity.getPaperTradeId(), entity);
            return entity;
        });
        when(repository.findByTradingDateBetweenOrderByTradingDateAscIdAsc(any(), any())).thenAnswer(inv -> {
            LocalDate start = inv.getArgument(0);
            LocalDate end = inv.getArgument(1);
            return rows.values().stream()
                    .filter(r -> !r.getTradingDate().isBefore(start) && !r.getTradingDate().isAfter(end))
                    .toList();
        });
        when(repository.countByTradingDateBetween(any(), any())).thenAnswer(inv -> {
            LocalDate start = inv.getArgument(0);
            LocalDate end = inv.getArgument(1);
            return rows.values().stream()
                    .filter(r -> !r.getTradingDate().isBefore(start) && !r.getTradingDate().isAfter(end))
                    .count();
        });
        return repository;
    }
}
