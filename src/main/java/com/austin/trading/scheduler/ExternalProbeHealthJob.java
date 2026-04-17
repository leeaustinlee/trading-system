package com.austin.trading.scheduler;

import com.austin.trading.dto.response.ExternalProbeItemResponse;
import com.austin.trading.dto.response.ExternalProbeResponse;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.ExternalProbeService;
import com.austin.trading.service.SchedulerLogService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "trading.scheduler.external-probe-health", name = "enabled", havingValue = "true")
public class ExternalProbeHealthJob {

    private final ExternalProbeService externalProbeService;
    private final LineTemplateService lineTemplateService;
    private final SchedulerLogService schedulerLogService;

    public ExternalProbeHealthJob(
            ExternalProbeService externalProbeService,
            LineTemplateService lineTemplateService,
            SchedulerLogService schedulerLogService
    ) {
        this.externalProbeService = externalProbeService;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
    }

    @Scheduled(cron = "${trading.scheduler.external-probe-health-cron:0 25 8,15 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "ExternalProbeHealthJob";
        try {
            ExternalProbeResponse probe = externalProbeService.probe(LocalDate.now(), false, false);
            List<String> failures = collectFailures(probe);

            if (!failures.isEmpty()) {
                String alert = "外部服務探針異常：\n- " + String.join("\n- ", failures);
                lineTemplateService.notifySystemAlert("外部探針異常", alert);
            }

            String msg = failures.isEmpty() ? "all_ok" : "failures=" + failures.size();
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(), msg);
        } catch (Exception e) {
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    private List<String> collectFailures(ExternalProbeResponse probe) {
        List<String> failures = new ArrayList<>();
        appendIfFailed(failures, "TAIFEX", probe.taifex());
        appendIfFailed(failures, "LINE", probe.line());
        appendIfFailed(failures, "CLAUDE", probe.claude());
        return failures;
    }

    private void appendIfFailed(List<String> failures, String name, ExternalProbeItemResponse item) {
        if (item == null) return;
        if ("SKIPPED".equalsIgnoreCase(item.status())) return;
        if (!item.success()) {
            failures.add(name + " [" + item.status() + "] " + item.detail());
        }
    }
}
