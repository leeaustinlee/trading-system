package com.austin.trading.scheduler;

import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.FinalDecisionService;
import com.austin.trading.service.SchedulerLogService;
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

    private final FinalDecisionService finalDecisionService;
    private final LineTemplateService lineTemplateService;
    private final SchedulerLogService schedulerLogService;

    public FinalDecision0930Job(
            FinalDecisionService finalDecisionService,
            LineTemplateService lineTemplateService,
            SchedulerLogService schedulerLogService
    ) {
        this.finalDecisionService = finalDecisionService;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.final-decision-cron:0 30 9 * * MON-FRI}", zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "FinalDecision0930Job";
        try {
            LocalDate today = LocalDate.now();
            FinalDecisionResponse result = finalDecisionService.evaluateAndPersist(today);

            // LINE 通知（含建議倉位與停損停利）
            lineTemplateService.notifyFinalDecision(result, today);

            String msg = "decision=" + result.decision() + ", selected=" + result.selectedStocks().size();
            log.info("[FinalDecision0930Job] {}", msg);
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), msg);
        } catch (Exception e) {
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }
}
