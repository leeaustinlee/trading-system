package com.austin.trading.repository;

import com.austin.trading.entity.MonitorDecisionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MonitorDecisionRepository extends JpaRepository<MonitorDecisionEntity, Long> {

    Optional<MonitorDecisionEntity> findTopByOrderByTradingDateDescDecisionTimeDescCreatedAtDesc();

    List<MonitorDecisionEntity> findAllByOrderByTradingDateDescDecisionTimeDescCreatedAtDesc(Pageable pageable);
}
