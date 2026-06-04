package com.austin.trading.scheduler;

import com.austin.trading.service.BuildOperationsService;
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
@ConditionalOnProperty(prefix = "trading.scheduler.theme-ops-daily-build", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ThemeOpsDailyBuildJob {
    private static final Logger log = LoggerFactory.getLogger(ThemeOpsDailyBuildJob.class);
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Taipei");
    private static final String JOB_NAME = "ThemeOpsDailyBuildJob";

    private final BuildOperationsService buildOperationsService;
    private final SchedulerLogService schedulerLogService;

    public ThemeOpsDailyBuildJob(BuildOperationsService buildOperationsService,
                                 SchedulerLogService schedulerLogService) {
        this.buildOperationsService = buildOperationsService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.theme-ops-daily-build-cron:0 25 15 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime started = LocalDateTime.now(MARKET_ZONE);
        LocalDate tradingDate = LocalDate.now(MARKET_ZONE);
        try {
            Map<String, Object> result = buildOperationsService.buildDailyThemeOps(tradingDate);
            String message = "themeOpsDailyBuild SUCCESS date=" + tradingDate + " layers=" + (result.size() - 1);
            log.info("[{}] {}", JOB_NAME, message);
            schedulerLogService.successReal(JOB_NAME, started, LocalDateTime.now(MARKET_ZONE), message);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("[{}] FAILED date={} error={}", JOB_NAME, tradingDate, message, e);
            schedulerLogService.failed(JOB_NAME, started, LocalDateTime.now(MARKET_ZONE), message);
            throw new RuntimeException(e);
        }
    }
}
