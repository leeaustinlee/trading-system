package com.austin.trading.scheduler;

import com.austin.trading.service.PromotionValidationDailySummaryService;
import com.austin.trading.service.SchedulerLogService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PromotionValidationDailySummaryJobTest {

    @Test
    void runForDateDelegatesToReportOnlySummaryService() {
        PromotionValidationDailySummaryService summaryService = mock(PromotionValidationDailySummaryService.class);
        SchedulerLogService logService = mock(SchedulerLogService.class);
        LocalDate date = LocalDate.of(2026, 6, 19);
        when(summaryService.run(date)).thenReturn(Map.of(
                "dailyValidationSummaryOnly", true,
                "overallStatus", "BLOCKED_BY_DATA_GAP",
                "itemCount", 1));

        PromotionValidationDailySummaryJob job = new PromotionValidationDailySummaryJob(summaryService, logService);
        Map<String, Object> result = job.runForDate(date);

        assertThat(result).containsEntry("dailyValidationSummaryOnly", true)
                .containsEntry("overallStatus", "BLOCKED_BY_DATA_GAP");
        verify(summaryService).run(date);
        verifyNoInteractions(logService);
    }

    @Test
    void scheduledRunWritesSchedulerSuccessLog() {
        PromotionValidationDailySummaryService summaryService = mock(PromotionValidationDailySummaryService.class);
        SchedulerLogService logService = mock(SchedulerLogService.class);
        when(summaryService.run(any(LocalDate.class))).thenReturn(Map.of(
                "dailyValidationSummaryOnly", true,
                "overallStatus", "NEED_MORE_EVIDENCE",
                "itemCount", 0));

        PromotionValidationDailySummaryJob job = new PromotionValidationDailySummaryJob(summaryService, logService);
        job.run();

        verify(logService).successReal(eq("PromotionValidationDailySummaryJob"), any(), any(),
                org.mockito.ArgumentMatchers.contains("overallStatus=NEED_MORE_EVIDENCE"));
    }
}
