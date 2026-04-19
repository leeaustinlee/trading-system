package com.austin.trading.repository;

import com.austin.trading.entity.SchedulerExecutionLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SchedulerExecutionLogRepository extends JpaRepository<SchedulerExecutionLogEntity, Long> {

    List<SchedulerExecutionLogEntity> findAllByOrderByTriggerTimeDesc(Pageable pageable);

    List<SchedulerExecutionLogEntity> findByJobNameOrderByTriggerTimeDesc(String jobName, Pageable pageable);

    Optional<SchedulerExecutionLogEntity> findTopByJobNameOrderByTriggerTimeDesc(String jobName);
}
