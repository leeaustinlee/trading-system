package com.austin.trading.scheduler;

import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.regime.MarketIndexBackfillService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0.5 — {@link MarketIndexDataPrepJob} 單元測試。覆蓋成功 / 失敗兩條路徑。
 */
class MarketIndexDataPrepJobTests {

    private final MarketIndexBackfillService backfill = mock(MarketIndexBackfillService.class);
    private final SchedulerLogService        logSvc   = mock(SchedulerLogService.class);

    @Test
    void run_success_logsSuccessAndDoesNotThrow() {
        when(backfill.dailyRefresh(any(LocalDate.class))).thenReturn(45);

        MarketIndexDataPrepJob job = new MarketIndexDataPrepJob(backfill, logSvc);
        job.run();

        verify(backfill, atLeastOnce()).dailyRefresh(any(LocalDate.class));
        verify(logSvc).success(eq("MarketIndexDataPrepJob"),
                any(LocalDateTime.class), any(LocalDateTime.class),
                contains("upserted=45"));
        verify(logSvc, never()).failed(anyString(), any(), any(), anyString());
    }

    @Test
    void run_backfillThrows_doesNotPropagateAndLogsFailure() {
        when(backfill.dailyRefresh(any(LocalDate.class)))
                .thenThrow(new RuntimeException("TWSE 503"));

        MarketIndexDataPrepJob job = new MarketIndexDataPrepJob(backfill, logSvc);

        // fail-safe — 不該丟出 RuntimeException 把 scheduler 卡住
        assertThatCode(job::run).doesNotThrowAnyException();
        verify(logSvc).failed(eq("MarketIndexDataPrepJob"),
                any(LocalDateTime.class), any(LocalDateTime.class),
                contains("TWSE 503"));
        verify(logSvc, never()).success(anyString(), any(), any(), anyString());
    }

    @Test
    void run_zeroUpsert_stillSuccess() {
        when(backfill.dailyRefresh(any(LocalDate.class))).thenReturn(0);

        MarketIndexDataPrepJob job = new MarketIndexDataPrepJob(backfill, logSvc);
        job.run();

        verify(logSvc).success(eq("MarketIndexDataPrepJob"),
                any(LocalDateTime.class), any(LocalDateTime.class),
                contains("upserted=0"));
        // 確認 message format
        assertThat(true).isTrue(); // sanity
    }
}
