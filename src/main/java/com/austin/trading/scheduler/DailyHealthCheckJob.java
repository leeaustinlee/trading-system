package com.austin.trading.scheduler;

import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.entity.FinalDecisionEntity;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.repository.AiTaskRepository;
import com.austin.trading.repository.FinalDecisionRepository;
import com.austin.trading.service.DailyOrchestrationService;
import com.austin.trading.service.OrchestrationStep;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 每日 08:00 系統健康檢查。
 *
 * <p>除了原本的 orchestration step 完成度檢查，v2.13 加上下列基礎健康指標：</p>
 * <ol>
 *   <li>DB ping — 透過 {@link DataSource} 檢查連線是否正常</li>
 *   <li>AI task 卡住 — RUNNING 狀態超過 1 小時的數量</li>
 *   <li>claude-submit 目錄堆積 — 主目錄 *.json/*.json.tmp 檔案總數</li>
 *   <li>今日 final_decision 紀錄 — 是否已有當日決策</li>
 *   <li>require_codex=true 但今日 OPENING task Codex 尚未完成（提示用，非強制）</li>
 * </ol>
 *
 * <p>任一檢查失敗 → 發 SYSTEM_ALERT LINE + log.warn。</p>
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.daily-health-check",
        name = "enabled", havingValue = "true")
public class DailyHealthCheckJob {

    private static final Logger log = LoggerFactory.getLogger(DailyHealthCheckJob.class);

    /** AI task 進入 RUNNING 後若超過此分鐘數仍未轉為 DONE/FAILED，視為卡住。 */
    private static final int AI_TASK_STUCK_MINUTES = 60;

    /** claude-submit 主目錄超過此檔案數量視為堆積。 */
    private static final int CLAUDE_SUBMIT_DIR_WARN_THRESHOLD = 30;

    /** RUNNING 狀態 prefix 集合。 */
    private static final List<String> RUNNING_STATUSES =
            List.of("CLAUDE_RUNNING", "CODEX_RUNNING", "RUNNING");

    private final DailyOrchestrationService orchestrationService;
    private final LineTemplateService lineTemplateService;
    private final SchedulerLogService schedulerLogService;
    private final ScoreConfigService scoreConfig;
    private final DataSource dataSource;
    private final AiTaskRepository aiTaskRepository;
    private final FinalDecisionRepository finalDecisionRepository;
    private final String claudeSubmitWatchDir;

    public DailyHealthCheckJob(DailyOrchestrationService orchestrationService,
                                LineTemplateService lineTemplateService,
                                SchedulerLogService schedulerLogService,
                                ScoreConfigService scoreConfig,
                                DataSource dataSource,
                                AiTaskRepository aiTaskRepository,
                                FinalDecisionRepository finalDecisionRepository,
                                @Value("${trading.claude-submit.watch-dir:/mnt/d/ai/stock/claude-submit}")
                                String claudeSubmitWatchDir) {
        this.orchestrationService = orchestrationService;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
        this.scoreConfig = scoreConfig;
        this.dataSource = dataSource;
        this.aiTaskRepository = aiTaskRepository;
        this.finalDecisionRepository = finalDecisionRepository;
        this.claudeSubmitWatchDir = claudeSubmitWatchDir;
    }

    @Scheduled(cron = "${trading.scheduler.daily-health-check-cron:0 0 8 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        runHealthCheck();
    }

    /** 對外可呼叫的 dry-run / 測試入口。回傳 alert message（健康時為 null）。 */
    public String runHealthCheck() {
        LocalDateTime trigger = LocalDateTime.now();
        String jobName = "DailyHealthCheckJob";
        try {
            LocalDate today = LocalDate.now();
            LocalDate yesterday = previousTradingDay(today);

            List<String> swept = orchestrationService.sweepStaleRunning(30, 2);
            List<OrchestrationStep> incomplete = orchestrationService.listIncompleteSteps(yesterday);

            // ── v2.13 系統健康檢查 ─────────────────────────────────────────
            List<String> systemIssues = new ArrayList<>();

            // 1. DB ping
            try (Connection conn = dataSource.getConnection()) {
                if (conn == null || !conn.isValid(3)) {
                    systemIssues.add("DB_PING_FAIL: connection invalid");
                }
            } catch (Exception e) {
                systemIssues.add("DB_PING_FAIL: " + e.getMessage());
            }

            // 2. AI task RUNNING > 1 小時
            try {
                LocalDateTime stuckBefore = LocalDateTime.now().minusMinutes(AI_TASK_STUCK_MINUTES);
                int stuckCount = countStuckAiTasks(stuckBefore);
                if (stuckCount > 0) {
                    systemIssues.add("AI_TASK_STUCK: " + stuckCount + " 個任務 RUNNING 超過 "
                            + AI_TASK_STUCK_MINUTES + " 分鐘");
                }
            } catch (Exception e) {
                systemIssues.add("AI_TASK_STUCK_CHECK_FAIL: " + e.getMessage());
            }

            // 3. claude-submit 目錄堆積
            try {
                int fileCount = countClaudeSubmitFiles(claudeSubmitWatchDir);
                if (fileCount > CLAUDE_SUBMIT_DIR_WARN_THRESHOLD) {
                    systemIssues.add("CLAUDE_SUBMIT_DIR_BACKLOG: " + fileCount
                            + " 檔（門檻 " + CLAUDE_SUBMIT_DIR_WARN_THRESHOLD + "）");
                }
            } catch (Exception e) {
                systemIssues.add("CLAUDE_SUBMIT_DIR_FAIL: " + e.getMessage());
            }

            // 4. 今日 final_decision 紀錄（僅交易日才檢查）
            if (isWeekday(today)) {
                try {
                    boolean hasToday = finalDecisionRepository.findAllByOrderByTradingDateDescCreatedAtDesc(
                                    org.springframework.data.domain.PageRequest.of(0, 5))
                            .stream()
                            .map(FinalDecisionEntity::getTradingDate)
                            .anyMatch(today::equals);
                    LocalDateTime nowDt = LocalDateTime.now();
                    // 09:30 前 final_decision 還沒寫是正常；只有 09:35 之後才提醒
                    if (!hasToday && nowDt.toLocalTime().isAfter(java.time.LocalTime.of(9, 35))) {
                        systemIssues.add("FINAL_DECISION_MISSING_TODAY: " + today + " 尚無 final_decision row");
                    }
                } catch (Exception e) {
                    systemIssues.add("FINAL_DECISION_CHECK_FAIL: " + e.getMessage());
                }
            }

            // 5. require_codex=true 但今日 OPENING task Codex 尚未完成（提示）
            try {
                boolean requireCodex = scoreConfig.getBoolean("final_decision.require_codex", true);
                if (requireCodex && isWeekday(today)) {
                    aiTaskRepository.findByTradingDateAndTaskTypeAndTargetSymbolIsNull(today, "OPENING")
                            .ifPresent(task -> {
                                String status = task.getStatus() == null ? "" : task.getStatus().toUpperCase();
                                if (!status.contains("DONE") && !"FINALIZED".equals(status)) {
                                    LocalDateTime nowDt = LocalDateTime.now();
                                    if (nowDt.toLocalTime().isAfter(java.time.LocalTime.of(9, 30))) {
                                        systemIssues.add("CODEX_NOT_READY_FOR_OPENING: "
                                                + "require_codex=true，OPENING task status=" + status);
                                    }
                                }
                            });
                }
            } catch (Exception e) {
                systemIssues.add("CODEX_READINESS_CHECK_FAIL: " + e.getMessage());
            }

            // ── 組裝結果 ───────────────────────────────────────────────────
            if (incomplete.isEmpty() && swept.isEmpty() && systemIssues.isEmpty()) {
                String msg = "昨日 (" + yesterday + ") 所有排程步驟完成 ✅；系統健康檢查全綠燈";
                log.info("[DailyHealthCheck] {}", msg);
                schedulerLogService.success(jobName, trigger, LocalDateTime.now(), msg);
                return null;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("⚠️ 每日系統健康檢查\n");
            sb.append("檢查日期：").append(today).append("\n");

            if (!incomplete.isEmpty()) {
                String missing = incomplete.stream()
                        .map(OrchestrationStep::name)
                        .collect(Collectors.joining(", "));
                sb.append("\n昨日 (").append(yesterday).append(") 未完成步驟 (")
                        .append(incomplete.size()).append(")：\n")
                        .append(missing).append("\n");
            }
            if (!swept.isEmpty()) {
                sb.append("\n被清理的卡死 RUNNING (").append(swept.size()).append(")：\n")
                        .append(String.join(", ", swept)).append("\n");
            }
            if (!systemIssues.isEmpty()) {
                sb.append("\n🚨 系統健康異常 (").append(systemIssues.size()).append(")：\n");
                for (String issue : systemIssues) {
                    sb.append("- ").append(issue).append("\n");
                }
            }

            if (!incomplete.isEmpty()) {
                sb.append("\n👉 用 POST /api/orchestration/recover?date=")
                        .append(yesterday).append(" 補跑");
            }

            String summary = sb.toString();
            log.warn("[DailyHealthCheck] 健康檢查發現異常:\n{}", summary);

            boolean lineEnabled = scoreConfig.getBoolean("scheduling.line_notify_enabled", false);
            if (lineEnabled) {
                lineTemplateService.notifySystemAlert("每日健康檢查", summary);
            }
            schedulerLogService.success(jobName, trigger, LocalDateTime.now(),
                    "incomplete=" + incomplete.size()
                            + " stale=" + swept.size()
                            + " systemIssues=" + systemIssues.size());
            return summary;
        } catch (Exception e) {
            log.error("[DailyHealthCheck] 健康檢查失敗: {}", e.getMessage(), e);
            schedulerLogService.failed(jobName, trigger, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    /** 計算 RUNNING 狀態超過 stuckBefore 的 ai_task 數量。 */
    int countStuckAiTasks(LocalDateTime stuckBefore) {
        int count = 0;
        for (String status : RUNNING_STATUSES) {
            for (AiTaskEntity t : aiTaskRepository.findByStatusOrderByCreatedAtAsc(status)) {
                LocalDateTime ref = pickRefTime(t, status);
                if (ref != null && ref.isBefore(stuckBefore)) count++;
            }
        }
        return count;
    }

    private LocalDateTime pickRefTime(AiTaskEntity t, String status) {
        if ("CLAUDE_RUNNING".equals(status)) return t.getClaudeStartedAt();
        if ("CODEX_RUNNING".equals(status))  return t.getCodexStartedAt();
        // generic RUNNING fallback to lastTransitionAt
        return t.getLastTransitionAt();
    }

    /** 計算 claude-submit 主目錄（不含 processed/failed/retry）內 *.json / *.json.tmp 檔案數量。 */
    int countClaudeSubmitFiles(String dirPath) throws IOException {
        Path dir = Path.of(dirPath);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return 0;
        try (Stream<Path> stream = Files.list(dir)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json") || n.endsWith(".json.tmp")
                            || n.endsWith(".processing"))
                    .count();
        }
    }

    private boolean isWeekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    /** 取上一個交易日（週一往前找週五、其他往前找一天） */
    private LocalDate previousTradingDay(LocalDate today) {
        LocalDate d = today.minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    /** 暴露測試用：檢查 stuck threshold/buffer。 */
    static Duration stuckThreshold() {
        return Duration.ofMinutes(AI_TASK_STUCK_MINUTES);
    }
}
