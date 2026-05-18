package com.austin.trading.service;

import com.austin.trading.domain.enums.SchedulerHealthLevel;
import com.austin.trading.entity.SchedulerExecutionLogEntity;
import com.austin.trading.repository.SchedulerExecutionLogRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

@Service
public class SchedulerLogService {

    private final SchedulerExecutionLogRepository schedulerExecutionLogRepository;

    public SchedulerLogService(SchedulerExecutionLogRepository schedulerExecutionLogRepository) {
        this.schedulerExecutionLogRepository = schedulerExecutionLogRepository;
    }

    /** Legacy success: method did not throw. Health level is inferred from the message for backward compatibility. */
    public void success(String jobName, LocalDateTime triggerTime, LocalDateTime finishedAt, String message) {
        save(jobName, triggerTime, finishedAt, "SUCCESS", inferHealthLevel("SUCCESS", message), message, message);
    }

    public void successReal(String jobName, LocalDateTime triggerTime, LocalDateTime finishedAt, String message) {
        save(jobName, triggerTime, finishedAt, "SUCCESS", SchedulerHealthLevel.SUCCESS_REAL, message, message);
    }

    public void successWithFallback(String jobName, LocalDateTime triggerTime, LocalDateTime finishedAt, String message) {
        save(jobName, triggerTime, finishedAt, "SUCCESS", SchedulerHealthLevel.SUCCESS_WITH_FALLBACK, message, message);
    }

    public void degraded(String jobName, LocalDateTime triggerTime, LocalDateTime finishedAt, String message) {
        save(jobName, triggerTime, finishedAt, "SUCCESS", SchedulerHealthLevel.DEGRADED, message, message);
    }

    public void emptyData(String jobName, LocalDateTime triggerTime, LocalDateTime finishedAt, String message) {
        save(jobName, triggerTime, finishedAt, "SUCCESS", SchedulerHealthLevel.EMPTY_DATA, message, message);
    }

    public void skipped(String jobName, LocalDateTime triggerTime, LocalDateTime finishedAt, String message) {
        save(jobName, triggerTime, finishedAt, "SUCCESS", SchedulerHealthLevel.SKIPPED, message, message);
    }

    public void failed(String jobName, LocalDateTime triggerTime, LocalDateTime finishedAt, String message) {
        save(jobName, triggerTime, finishedAt, "FAILED", SchedulerHealthLevel.FAILED, message, message);
    }

    private SchedulerHealthLevel inferHealthLevel(String status, String message) {
        if ("FAILED".equalsIgnoreCase(status)) {
            return SchedulerHealthLevel.FAILED;
        }
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("fallback") || normalized.contains("fallback_used")) {
            return SchedulerHealthLevel.SUCCESS_WITH_FALLBACK;
        }
        if (normalized.contains("degraded")
                || normalized.contains("incomplete")
                || normalized.contains("stale")
                || normalized.matches(".*failures=[1-9].*")) {
            return SchedulerHealthLevel.DEGRADED;
        }
        if (normalized.contains("no data")
                || normalized.contains("no market snapshot")
                || normalized.contains("no candidates")
                || normalized.contains("empty")
                || normalized.contains("upserted=0")) {
            return SchedulerHealthLevel.EMPTY_DATA;
        }
        if (normalized.contains("skip") || normalized.contains("skipped")) {
            return SchedulerHealthLevel.SKIPPED;
        }
        return SchedulerHealthLevel.SUCCESS_REAL;
    }

    private void save(
            String jobName,
            LocalDateTime triggerTime,
            LocalDateTime finishedAt,
            String status,
            SchedulerHealthLevel healthLevel,
            String healthReason,
            String message) {
        SchedulerExecutionLogEntity entity = new SchedulerExecutionLogEntity();
        entity.setJobName(jobName);
        entity.setTriggerTime(triggerTime);
        entity.setStatus(status);
        entity.setHealthLevel(healthLevel.name());
        entity.setHealthReason(truncate(healthReason));
        entity.setDurationMs(Duration.between(triggerTime, finishedAt).toMillis());
        entity.setMessage(truncate(message));
        schedulerExecutionLogRepository.save(entity);
    }

    private String truncate(String value) {
        return value == null ? null : value.substring(0, Math.min(value.length(), 500));
    }
}
