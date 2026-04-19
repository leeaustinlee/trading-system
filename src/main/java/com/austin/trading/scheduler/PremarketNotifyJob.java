package com.austin.trading.scheduler;

import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
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
    private final DailyOrchestrationService orchestrationService;

    public PremarketNotifyJob(
            PremarketWorkflowService workflowService,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService
    ) {
        this.workflowService    = workflowService;
        this.schedulerLogService = schedulerLogService;
        this.orchestrationService = orchestrationService;
    }

    @Scheduled(cron = "${trading.scheduler.premarket-notify-cron:0 30 8 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "PremarketNotifyJob";
        LocalDate today = LocalDate.now();
        OrchestrationStep step = OrchestrationStep.PREMARKET_NOTIFY;

        if (!orchestrationService.markRunning(today, step)) {
            log.info("[{}] Step {} already DONE today, skip.", jobName, step);
            return;
        }
        try {
            workflowService.execute(today);
            log.info("[PremarketNotifyJob] completed");
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), "workflow completed");
            orchestrationService.markDone(today, step, "workflow completed");
        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }
}
