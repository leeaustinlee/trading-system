package com.austin.trading.repository;

import com.austin.trading.entity.ExternalProbeLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExternalProbeLogRepository extends JpaRepository<ExternalProbeLogEntity, Long> {

    List<ExternalProbeLogEntity> findAllByOrderByCheckedAtDesc(Pageable pageable);
}
