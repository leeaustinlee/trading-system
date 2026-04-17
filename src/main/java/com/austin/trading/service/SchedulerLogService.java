package com.austin.trading.service;

import com.austin.trading.entity.SchedulerExecutionLogEntity;
import com.austin.trading.repository.SchedulerExecutionLogRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class SchedulerLogService {

    private final SchedulerExecutionLogRepository schedulerExecutionLogRepository;

    public SchedulerLogService(SchedulerExecutionLogRepository schedulerExecutionLogRepository) {
        this.schedulerExecutionLogRepository = schedulerExecutionLogRepository;
    }

    public void success(String jobName, LocalDateTime triggerTime, LocalDateTime finishedAt, String message) {
        save(jobName, triggerTime, finishedAt, "SUCCESS", message);
    }

    public void failed(String jobName, LocalDateTime triggerTime, LocalDateTime finishedAt, String message) {
        save(jobName, triggerTime, finishedAt, "FAILED", message);
    }

    private void save(String jobName, LocalDateTime triggerTime, LocalDateTime finishedAt, String status, String message) {
        SchedulerExecutionLogEntity entity = new SchedulerExecutionLogEntity();
        entity.setJobName(jobName);
        entity.setTriggerTime(triggerTime);
        entity.setStatus(status);
        entity.setDurationMs(Duration.between(triggerTime, finishedAt).toMillis());
        entity.setMessage(message == null ? null : message.substring(0, Math.min(message.length(), 500)));
        schedulerExecutionLogRepository.save(entity);
    }
}
