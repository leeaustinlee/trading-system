package com.austin.trading.scheduler;

import com.austin.trading.entity.AiTaskEntity;
import com.austin.trading.repository.AiTaskRepository;
import com.austin.trading.service.AiTaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * v2.1 AI Task Timeout Sweep（P1）。
 * <p>每 N 秒（預設 60）掃一次：</p>
 * <ul>
 *   <li>{@code PENDING} 超過 {@code ai.task.pending.timeout.minutes} → {@code EXPIRED}</li>
 *   <li>{@code CLAUDE_RUNNING} 超過 {@code ai.task.claude.timeout.minutes} → {@code FAILED}</li>
 *   <li>{@code CODEX_RUNNING} 超過 {@code ai.task.codex.timeout.minutes} → {@code FAILED}</li>
 * </ul>
 * <p>重跑須建新 task，不得把舊 task 從 FAILED/EXPIRED 倒退回 RUNNING。</p>
 */
@Component
@ConditionalOnProperty(prefix = "ai.task.sweep", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class AiTaskSweepJob {

    private static final Logger log = LoggerFactory.getLogger(AiTaskSweepJob.class);

    private final AiTaskRepository aiTaskRepository;
    private final AiTaskService aiTaskService;

    private final long claudeTimeoutMinutes;
    private final long codexTimeoutMinutes;
    private final long pendingTimeoutMinutes;

    public AiTaskSweepJob(
            AiTaskRepository aiTaskRepository,
            AiTaskService aiTaskService,
            @Value("${ai.task.claude.timeout.minutes:20}") long claudeTimeoutMinutes,
            @Value("${ai.task.codex.timeout.minutes:10}") long codexTimeoutMinutes,
            @Value("${ai.task.pending.timeout.minutes:30}") long pendingTimeoutMinutes
    ) {
        this.aiTaskRepository = aiTaskRepository;
        this.aiTaskService = aiTaskService;
        this.claudeTimeoutMinutes = claudeTimeoutMinutes;
        this.codexTimeoutMinutes = codexTimeoutMinutes;
        this.pendingTimeoutMinutes = pendingTimeoutMinutes;
    }

    @Scheduled(fixedDelayString = "${ai.task.sweep.interval.seconds:60}000")
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        int pendingExpired = 0, claudeFailed = 0, codexFailed = 0;

        List<AiTaskEntity> candidates = aiTaskRepository.findAll();
        for (AiTaskEntity t : candidates) {
            try {
                String status = t.getStatus();
                if (AiTaskService.STATUS_PENDING.equals(status)) {
                    LocalDateTime created = t.getCreatedAt();
                    if (created != null && minutesBetween(created, now) >= pendingTimeoutMinutes) {
                        aiTaskService.expireTask(t.getId(),
                                "AI_TIMEOUT: PENDING > " + pendingTimeoutMinutes + "m");
                        pendingExpired++;
                    }
                } else if (AiTaskService.STATUS_CLAUDE_RUNNING.equals(status)) {
                    LocalDateTime start = t.getClaudeStartedAt();
                    if (start != null && minutesBetween(start, now) >= claudeTimeoutMinutes) {
                        aiTaskService.failTask(t.getId(),
                                "AI_TIMEOUT: CLAUDE_RUNNING > " + claudeTimeoutMinutes + "m");
                        claudeFailed++;
                    }
                } else if (AiTaskService.STATUS_CODEX_RUNNING.equals(status)) {
                    LocalDateTime start = t.getCodexStartedAt();
                    if (start != null && minutesBetween(start, now) >= codexTimeoutMinutes) {
                        aiTaskService.failTask(t.getId(),
                                "AI_TIMEOUT: CODEX_RUNNING > " + codexTimeoutMinutes + "m");
                        codexFailed++;
                    }
                }
            } catch (Exception e) {
                log.warn("[AiTaskSweep] id={} 掃描失敗: {}", t.getId(), e.getMessage());
            }
        }
        if (pendingExpired + claudeFailed + codexFailed > 0) {
            log.info("[AiTaskSweep] pendingExpired={} claudeFailed={} codexFailed={}",
                    pendingExpired, claudeFailed, codexFailed);
        }
    }

    private long minutesBetween(LocalDateTime from, LocalDateTime to) {
        return java.time.Duration.between(from, to).toMinutes();
    }
}
