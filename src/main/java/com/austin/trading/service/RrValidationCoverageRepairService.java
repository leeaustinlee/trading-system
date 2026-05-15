package com.austin.trading.service;

import com.austin.trading.entity.RrShadowValidationEntity;
import com.austin.trading.repository.RrShadowValidationRepository;
import com.austin.trading.service.regime.MarketIndexSymbolBackfillService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class RrValidationCoverageRepairService {

    private final RrShadowValidationRepository validationRepository;
    private final MarketIndexSymbolBackfillService marketIndexSymbolBackfillService;
    private final CandidateForwardReturnBackfillService candidateForwardReturnBackfillService;
    private final RrShadowValidationService rrShadowValidationService;

    public RrValidationCoverageRepairService(RrShadowValidationRepository validationRepository,
                                             MarketIndexSymbolBackfillService marketIndexSymbolBackfillService,
                                             CandidateForwardReturnBackfillService candidateForwardReturnBackfillService,
                                             RrShadowValidationService rrShadowValidationService) {
        this.validationRepository = validationRepository;
        this.marketIndexSymbolBackfillService = marketIndexSymbolBackfillService;
        this.candidateForwardReturnBackfillService = candidateForwardReturnBackfillService;
        this.rrShadowValidationService = rrShadowValidationService;
    }

    public RepairResponse repairCoverage(int days) {
        int window = days > 0 ? days : 60;
        LocalDate windowEnd = LocalDate.now();
        LocalDate windowStart = windowEnd.minusDays(window);

        RrShadowValidationService.Summary before = rrShadowValidationService.summary(window);
        List<RrShadowValidationEntity> rows =
                validationRepository.findByTradingDateBetweenOrderByTradingDateAscIdAsc(windowStart, windowEnd);

        LinkedHashSet<String> symbols = new LinkedHashSet<>();
        LocalDate oldest = null;
        LocalDate newest = null;
        for (RrShadowValidationEntity row : rows) {
            if (row.getSymbol() != null && !row.getSymbol().isBlank()) symbols.add(row.getSymbol());
            if (row.getTradingDate() == null) continue;
            oldest = oldest == null || row.getTradingDate().isBefore(oldest) ? row.getTradingDate() : oldest;
            newest = newest == null || row.getTradingDate().isAfter(newest) ? row.getTradingDate() : newest;
        }

        LocalDate marketFrom = oldest == null ? windowStart : oldest.minusDays(5);
        LocalDate desiredTo = newest == null ? windowEnd : newest.plusDays(20);
        LocalDate marketTo = desiredTo.isAfter(windowEnd) ? windowEnd : desiredTo;

        Map<String, Object> marketBackfill = marketIndexSymbolBackfillService.backfillSymbols(
                marketFrom,
                marketTo,
                String.join(",", symbols),
                false,
                false,
                Math.max(symbols.size(), 1)
        );
        Map<String, Object> forwardBackfill = candidateForwardReturnBackfillService.backfillReturns(window);
        Map<String, Object> rrBackfill = rrShadowValidationService.backfillExpanded(window);
        RrShadowValidationService.Summary after = rrShadowValidationService.summary(window);

        Map<String, Object> upsertedRows = new LinkedHashMap<>();
        upsertedRows.put("marketIndexDaily", intValue(marketBackfill.get("upsertedRows")));
        upsertedRows.put("candidateForwardTracking", intValue(forwardBackfill.get("updatedRows")));
        upsertedRows.put("rrShadowValidation", intValue(rrBackfill.get("upsertedRows")));

        return new RepairResponse(
                window,
                marketFrom,
                marketTo,
                before,
                after,
                List.copyOf(symbols),
                upsertedRows,
                after.dataGaps(),
                after.coverageGaps().missingSymbols(),
                after.coverageGaps().missingBenchmark(),
                after.coverageGaps().missingHorizons(),
                after.coverageGaps().oldestEntryDate(),
                after.coverageGaps().newestEntryDate(),
                after.blockedReturnCoveragePct().subtract(before.blockedReturnCoveragePct()),
                marketBackfill,
                forwardBackfill,
                rrBackfill,
                "SHADOW_ONLY: repairs market_index_daily/candidate_forward_tracking coverage and rewrites rr_shadow_validation diagnostics only"
        );
    }

    private int intValue(Object value) {
        if (value instanceof Number n) return n.intValue();
        return 0;
    }

    public record RepairResponse(
            int days,
            LocalDate marketDataFrom,
            LocalDate marketDataTo,
            RrShadowValidationService.Summary before,
            RrShadowValidationService.Summary after,
            List<String> symbols,
            Map<String, Object> upsertedRows,
            Map<String, Integer> dataGaps,
            List<String> missingSymbols,
            List<String> missingBenchmark,
            Map<String, List<String>> missingHorizons,
            LocalDate oldestEntryDate,
            LocalDate newestEntryDate,
            BigDecimal blockedReturnCoveragePctDelta,
            Map<String, Object> marketBackfill,
            Map<String, Object> candidateForwardBackfill,
            Map<String, Object> rrShadowBackfill,
            String safety
    ) {}
}
