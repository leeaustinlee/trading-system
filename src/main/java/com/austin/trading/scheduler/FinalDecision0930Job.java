package com.austin.trading.scheduler;

import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.workflow.IntradayDecisionWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
@ConditionalOnProperty(prefix = "trading.scheduler.final-decision", name = "enabled", havingValue = "true")
public class FinalDecision0930Job {

    private static final Logger log = LoggerFactory.getLogger(FinalDecision0930Job.class);

    private final IntradayDecisionWorkflowService workflowService;
    private final SchedulerLogService schedulerLogService;
    private final DailyOrchestrationService orchestrationService;

    public FinalDecision0930Job(
            IntradayDecisionWorkflowService workflowService,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService
    ) {
        this.workflowService    = workflowService;
        this.schedulerLogService = schedulerLogService;
        this.orchestrationService = orchestrationService;
    }

    @Scheduled(cron = "${trading.scheduler.final-decision-cron:0 30 9 * * MON-FRI}", zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "FinalDecision0930Job";
        LocalDate today = LocalDate.now();
        OrchestrationStep step = OrchestrationStep.FINAL_DECISION;

        if (!orchestrationService.markRunning(today, step)) {
            log.info("[{}] Step {} already DONE today, skip.", jobName, step);
            return;
        }
        try {
            workflowService.execute(today);
            log.info("[FinalDecision0930Job] completed");
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), "workflow completed");
            orchestrationService.markDone(today, step, "workflow completed");
        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }
}
