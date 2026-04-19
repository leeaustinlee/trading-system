package com.austin.trading.scheduler;

import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 每日 08:00 健康檢查 — 檢查昨日（交易日）所有 orchestration step 是否完成。
 *
 * <p>有未完成步驟時發 LINE 通知 + log.warn，讓 Austin 起床就知道系統昨晚健康狀況。</p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.daily-health-check",
        name = "enabled", havingValue = "true")
public class DailyHealthCheckJob {

    private static final Logger log = LoggerFactory.getLogger(DailyHealthCheckJob.class);

    private final DailyOrchestrationService orchestrationService;
    private final LineTemplateService lineTemplateService;
    private final SchedulerLogService schedulerLogService;
    private final ScoreConfigService scoreConfig;

    public DailyHealthCheckJob(DailyOrchestrationService orchestrationService,
                                LineTemplateService lineTemplateService,
                                SchedulerLogService schedulerLogService,
                                ScoreConfigService scoreConfig) {
        this.orchestrationService = orchestrationService;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
        this.scoreConfig = scoreConfig;
    }

    @Scheduled(cron = "${trading.scheduler.daily-health-check-cron:0 0 8 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime trigger = LocalDateTime.now();
        String jobName = "DailyHealthCheckJob";
        try {
            LocalDate yesterday = previousTradingDay(LocalDate.now());

            // Sweep stale RUNNING（最近 2 天）
            List<String> swept = orchestrationService.sweepStaleRunning(30, 2);

            // 列出昨日未完成步驟
            List<OrchestrationStep> incomplete = orchestrationService.listIncompleteSteps(yesterday);

            if (incomplete.isEmpty() && swept.isEmpty()) {
                String msg = "昨日 (" + yesterday + ") 所有排程步驟完成 ✅";
                log.info("[DailyHealthCheck] {}", msg);
                schedulerLogService.success(jobName, trigger, LocalDateTime.now(), msg);
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("⚠️ 昨日系統健康檢查\n");
            sb.append("日期：").append(yesterday).append("\n");

            if (!incomplete.isEmpty()) {
                String missing = incomplete.stream()
                        .map(OrchestrationStep::name)
                        .collect(Collectors.joining(", "));
                sb.append("\n未完成步驟 (").append(incomplete.size()).append(")：\n")
                        .append(missing).append("\n");
            }
            if (!swept.isEmpty()) {
                sb.append("\n被清理的卡死 RUNNING (").append(swept.size()).append(")：\n")
                        .append(String.join(", ", swept)).append("\n");
            }

            sb.append("\n👉 用 POST /api/orchestration/recover?date=")
                    .append(yesterday).append(" 補跑");

            String summary = sb.toString();
            log.warn("[DailyHealthCheck] 發現昨日未完成步驟:\n{}", summary);

            boolean lineEnabled = scoreConfig.getBoolean("scheduling.line_notify_enabled", false);
            if (lineEnabled) {
                lineTemplateService.notifySystemAlert("每日健康檢查", summary);
            }
            schedulerLogService.success(jobName, trigger, LocalDateTime.now(),
                    "incomplete=" + incomplete.size() + " stale=" + swept.size());
        } catch (Exception e) {
            log.error("[DailyHealthCheck] 健康檢查失敗: {}", e.getMessage(), e);
            schedulerLogService.failed(jobName, trigger, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    /** 取上一個交易日（週一往前找週五、其他往前找一天） */
    private LocalDate previousTradingDay(LocalDate today) {
        LocalDate d = today.minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }
}
