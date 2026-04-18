package com.austin.trading.scheduler;

import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.workflow.PremarketWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "trading.scheduler.premarket-notify", name = "enabled", havingValue = "true")
public class PremarketNotifyJob {

    private static final Logger log = LoggerFactory.getLogger(PremarketNotifyJob.class);

    private final PremarketWorkflowService workflowService;
    private final SchedulerLogService      schedulerLogService;

    public PremarketNotifyJob(
            PremarketWorkflowService workflowService,
            SchedulerLogService schedulerLogService
    ) {
        this.workflowService    = workflowService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.premarket-notify-cron:0 30 8 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "PremarketNotifyJob";
        try {
            workflowService.execute(LocalDate.now());
            log.info("[PremarketNotifyJob] completed");
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), "workflow completed");
        } catch (Exception e) {
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }
}
