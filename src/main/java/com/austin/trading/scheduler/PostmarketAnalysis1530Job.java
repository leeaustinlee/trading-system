package com.austin.trading.scheduler;

import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.workflow.PostmarketWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 15:30 盤後分析排程。
 * 所有業務邏輯委派給 {@link PostmarketWorkflowService}。
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.postmarket-analysis", name = "enabled", havingValue = "true")
public class PostmarketAnalysis1530Job {

    private static final Logger log = LoggerFactory.getLogger(PostmarketAnalysis1530Job.class);

    private final PostmarketWorkflowService workflowService;
    private final SchedulerLogService       schedulerLogService;

    public PostmarketAnalysis1530Job(
            PostmarketWorkflowService workflowService,
            SchedulerLogService schedulerLogService
    ) {
        this.workflowService    = workflowService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.postmarket-analysis-cron:0 30 15 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "PostmarketAnalysis1530Job";
        try {
            workflowService.execute(LocalDate.now());
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), "ok");
        } catch (Exception e) {
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }
}
