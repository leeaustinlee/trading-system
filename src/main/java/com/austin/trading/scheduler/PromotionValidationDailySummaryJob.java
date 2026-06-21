package com.austin.trading.scheduler;

import com.austin.trading.service.PromotionValidationDailySummaryService;
import com.austin.trading.service.SchedulerLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "trading.scheduler.promotion-validation-daily-summary", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PromotionValidationDailySummaryJob {
    private static final Logger log = LoggerFactory.getLogger(PromotionValidationDailySummaryJob.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Taipei");
    private static final String JOB_NAME = "PromotionValidationDailySummaryJob";

    private final PromotionValidationDailySummaryService summaryService;
    private final SchedulerLogService schedulerLogService;

    public PromotionValidationDailySummaryJob(PromotionValidationDailySummaryService summaryService,
                                              SchedulerLogService schedulerLogService) {
        this.summaryService = summaryService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.promotion-validation-daily-summary-cron:0 40 15 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime started = LocalDateTime.now(MARKET_ZONE);
        LocalDate tradingDate = LocalDate.now(MARKET_ZONE);
        try {
            Map<String, Object> result = runForDate(tradingDate);
            String message = "promotionValidationDailySummary SUCCESS date=" + tradingDate
                    + " overallStatus=" + result.get("overallStatus")
                    + " items=" + result.get("itemCount");
            log.info("[{}] {}", JOB_NAME, message);
            schedulerLogService.successReal(JOB_NAME, started, LocalDateTime.now(MARKET_ZONE), message);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("[{}] FAILED date={} error={}", JOB_NAME, tradingDate, message, e);
            schedulerLogService.failed(JOB_NAME, started, LocalDateTime.now(MARKET_ZONE), message);
            throw new RuntimeException(e);
        }
    }

    public Map<String, Object> runForDate(LocalDate tradingDate) {
        return summaryService.run(tradingDate);
    }
}
