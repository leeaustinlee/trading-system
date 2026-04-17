package com.austin.trading.repository;

import com.austin.trading.entity.SchedulerExecutionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchedulerExecutionLogRepository extends JpaRepository<SchedulerExecutionLogEntity, Long> {
}
