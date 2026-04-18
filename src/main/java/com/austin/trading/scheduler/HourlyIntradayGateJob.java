package com.austin.trading.scheduler;

import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.workflow.HourlyGateWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Component
@ConditionalOnProperty(prefix = "trading.scheduler.hourly-gate", name = "enabled", havingValue = "true")
public class HourlyIntradayGateJob {

    private static final Logger log = LoggerFactory.getLogger(HourlyIntradayGateJob.class);

    private final HourlyGateWorkflowService workflowService;
    private final SchedulerLogService       schedulerLogService;

    public HourlyIntradayGateJob(
            HourlyGateWorkflowService workflowService,
            SchedulerLogService schedulerLogService
    ) {
        this.workflowService    = workflowService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.hourly-gate-cron:0 5 10-13 * * MON-FRI}", zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "HourlyIntradayGateJob";
        try {
            workflowService.execute(LocalDate.now(), LocalTime.now());
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), "ok");
        } catch (Exception e) {
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }
}
