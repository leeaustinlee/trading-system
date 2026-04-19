package com.austin.trading.scheduler;

import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
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
    private final DailyOrchestrationService orchestrationService;

    public HourlyIntradayGateJob(
            HourlyGateWorkflowService workflowService,
            SchedulerLogService schedulerLogService,
            DailyOrchestrationService orchestrationService
    ) {
        this.workflowService    = workflowService;
        this.schedulerLogService = schedulerLogService;
        this.orchestrationService = orchestrationService;
    }

    @Scheduled(cron = "${trading.scheduler.hourly-gate-cron:0 5 10-13 * * MON-FRI}", zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "HourlyIntradayGateJob";
        LocalDate today = LocalDate.now();
        OrchestrationStep step = OrchestrationStep.HOURLY_GATE;

        // 一天跑多次：不做 DONE 阻擋，執行後呼叫 markExecuted 更新 updated_at
        try {
            workflowService.execute(today, LocalTime.now());
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), "ok");
            orchestrationService.markExecuted(today, step, "executed at " + LocalTime.now());
        } catch (Exception e) {
            orchestrationService.markFailed(today, step, e.getMessage());
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }
}
