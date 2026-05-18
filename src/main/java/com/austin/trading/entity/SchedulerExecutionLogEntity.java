package com.austin.trading.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "scheduler_execution_log")
public class SchedulerExecutionLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_name", nullable = false, length = 120)
    private String jobName;

    @Column(name = "trigger_time", nullable = false)
    private LocalDateTime triggerTime;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "health_level", length = 40)
    private String healthLevel;

    @Column(name = "health_reason", length = 500)
    private String healthReason;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }
    public LocalDateTime getTriggerTime() { return triggerTime; }
    public void setTriggerTime(LocalDateTime triggerTime) { this.triggerTime = triggerTime; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHealthLevel() { return healthLevel != null && !healthLevel.isBlank() ? healthLevel : inferHealthLevel(); }
    public void setHealthLevel(String healthLevel) { this.healthLevel = healthLevel; }
    public String getHealthReason() { return healthReason != null && !healthReason.isBlank() ? healthReason : message; }
    public void setHealthReason(String healthReason) { this.healthReason = healthReason; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    private String inferHealthLevel() {
        if ("FAILED".equalsIgnoreCase(status)) {
            return "FAILED";
        }
        String normalized = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("fallback") || normalized.contains("fallback_used")) {
            return "SUCCESS_WITH_FALLBACK";
        }
        if (normalized.contains("degraded")
                || normalized.contains("incomplete")
                || normalized.contains("stale")
                || normalized.matches(".*failures=[1-9].*")) {
            return "DEGRADED";
        }
        if (normalized.contains("no data")
                || normalized.contains("no market snapshot")
                || normalized.contains("no candidates")
                || normalized.contains("empty")
                || normalized.contains("upserted=0")) {
            return "EMPTY_DATA";
        }
        if (normalized.contains("skip") || normalized.contains("skipped")) {
            return "SKIPPED";
        }
        return "SUCCESS_REAL";
    }
}
