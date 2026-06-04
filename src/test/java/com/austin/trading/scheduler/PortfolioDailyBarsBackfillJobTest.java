package com.austin.trading.scheduler;

import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.regime.MarketIndexSymbolBackfillService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioDailyBarsBackfillJobTest {

    @Test
    void runBackfillsOpenPositionDailyBarsAfterMarket() {
        MarketIndexSymbolBackfillService backfillService = mock(MarketIndexSymbolBackfillService.class);
        SchedulerLogService schedulerLogService = mock(SchedulerLogService.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resolvedSymbols", List.of("4938"));
        result.put("upsertedRows", 12);
        result.put("benchmarkDataGap", false);
        when(backfillService.backfillSymbols(120, null, false, false, 50)).thenReturn(result);

        PortfolioDailyBarsBackfillJob job = new PortfolioDailyBarsBackfillJob(backfillService, schedulerLogService);
        job.run();

        verify(backfillService).backfillSymbols(120, null, false, false, 50);
        verify(schedulerLogService).successReal(
                contains("PortfolioDailyBarsBackfillJob"),
                any(),
                any(),
                contains("4938"));
    }
}
