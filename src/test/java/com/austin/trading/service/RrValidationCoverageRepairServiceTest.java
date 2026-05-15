package com.austin.trading.service;

import com.austin.trading.entity.RrShadowValidationEntity;
import com.austin.trading.repository.RrShadowValidationRepository;
import com.austin.trading.service.regime.MarketIndexSymbolBackfillService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RrValidationCoverageRepairServiceTest {

    @Test
    void repairCoverageRunsBackfillSequenceAndReturnsBeforeAfter() {
        RrShadowValidationRepository validationRepo = mock(RrShadowValidationRepository.class);
        MarketIndexSymbolBackfillService marketBackfill = mock(MarketIndexSymbolBackfillService.class);
        CandidateForwardReturnBackfillService forwardBackfill = mock(CandidateForwardReturnBackfillService.class);
        RrShadowValidationService rrService = mock(RrShadowValidationService.class);
        LocalDate entryDate = LocalDate.now().minusDays(12);
        RrShadowValidationEntity row = new RrShadowValidationEntity();
        row.setTradingDate(entryDate);
        row.setSymbol("2330");

        when(rrService.summary(60)).thenReturn(
                summary("0.00", List.of("2330@" + entryDate)),
                summary("100.00", List.of())
        );
        when(validationRepo.findByTradingDateBetweenOrderByTradingDateAscIdAsc(any(), any()))
                .thenReturn(List.of(row));
        when(marketBackfill.backfillSymbols(any(LocalDate.class), any(LocalDate.class), eq("2330"),
                eq(false), eq(false), eq(1)))
                .thenReturn(Map.of("upsertedRows", 3));
        when(forwardBackfill.backfillReturns(60)).thenReturn(Map.of("updatedRows", 2));
        when(rrService.backfill(60)).thenReturn(Map.of("upsertedRows", 1));

        RrValidationCoverageRepairService service = new RrValidationCoverageRepairService(
                validationRepo, marketBackfill, forwardBackfill, rrService);

        RrValidationCoverageRepairService.RepairResponse response = service.repairCoverage(60);

        assertThat(response.before().blockedReturnCoveragePct()).isEqualByComparingTo("0.00");
        assertThat(response.after().blockedReturnCoveragePct()).isEqualByComparingTo("100.00");
        assertThat(response.symbols()).containsExactly("2330");
        assertThat(response.upsertedRows()).containsEntry("marketIndexDaily", 3)
                .containsEntry("candidateForwardTracking", 2)
                .containsEntry("rrShadowValidation", 1);
        assertThat(response.blockedReturnCoveragePctDelta()).isEqualByComparingTo("100.00");
        assertThat(response.missingSymbols()).isEmpty();
        assertThat(response.safety()).contains("SHADOW_ONLY");

        InOrder inOrder = inOrder(rrService, marketBackfill, forwardBackfill);
        inOrder.verify(rrService).summary(60);
        inOrder.verify(marketBackfill).backfillSymbols(any(LocalDate.class), any(LocalDate.class), eq("2330"),
                eq(false), eq(false), eq(1));
        inOrder.verify(forwardBackfill).backfillReturns(60);
        inOrder.verify(rrService).backfill(60);
        inOrder.verify(rrService).summary(60);
    }

    private RrShadowValidationService.Summary summary(String coverage, List<String> missingSymbols) {
        Map<String, Integer> dataGaps = new LinkedHashMap<>();
        dataGaps.put("T1", missingSymbols.isEmpty() ? 0 : 1);
        dataGaps.put("T3", missingSymbols.isEmpty() ? 0 : 1);
        dataGaps.put("T5", missingSymbols.isEmpty() ? 0 : 1);
        dataGaps.put("T10", missingSymbols.isEmpty() ? 0 : 1);
        LocalDate date = LocalDate.now().minusDays(12);
        return new RrShadowValidationService.Summary(
                60, date.minusDays(48), LocalDate.now(),
                1, 1, missingSymbols.isEmpty() ? 0 : 1, 1, new BigDecimal("100.00"),
                null, null, null, null,
                dataGaps, 0, 0, Map.of("STOP_TOO_WIDE", 1L), List.of("2330"),
                new BigDecimal(coverage),
                new RrShadowValidationService.CoverageGapDetails(
                        missingSymbols, List.of(), Map.of(), date, date)
        );
    }
}
