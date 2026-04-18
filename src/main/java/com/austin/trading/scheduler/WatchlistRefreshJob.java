package com.austin.trading.scheduler;

import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.workflow.WatchlistWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "trading.scheduler.watchlist-refresh", name = "enabled", havingValue = "true")
public class WatchlistRefreshJob {

    private static final Logger log = LoggerFactory.getLogger(WatchlistRefreshJob.class);

    private final WatchlistWorkflowService watchlistWorkflowService;
    private final SchedulerLogService schedulerLogService;

    public WatchlistRefreshJob(WatchlistWorkflowService watchlistWorkflowService,
                               SchedulerLogService schedulerLogService) {
        this.watchlistWorkflowService = watchlistWorkflowService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.watchlist-refresh-cron:0 35 15 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "WatchlistRefreshJob";
        try {
            watchlistWorkflowService.execute(LocalDate.now());
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), "Watchlist refresh done.");
        } catch (Exception e) {
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }
}
