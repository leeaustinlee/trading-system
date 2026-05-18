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
    public String getHealthLevel() { return healthLevel; }
    public void setHealthLevel(String healthLevel) { this.healthLevel = healthLevel; }
    public String getHealthReason() { return healthReason; }
    public void setHealthReason(String healthReason) { this.healthReason = healthReason; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
