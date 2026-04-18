package com.austin.trading.scheduler;

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

    public FinalDecision0930Job(
            IntradayDecisionWorkflowService workflowService,
            SchedulerLogService schedulerLogService
    ) {
        this.workflowService    = workflowService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.final-decision-cron:0 30 9 * * MON-FRI}", zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "FinalDecision0930Job";
        try {
            workflowService.execute(LocalDate.now());
            log.info("[FinalDecision0930Job] completed");
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), "workflow completed");
        } catch (Exception e) {
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }
}
