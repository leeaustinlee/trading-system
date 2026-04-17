package com.austin.trading.repository;

import com.austin.trading.entity.HourlyGateDecisionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HourlyGateDecisionRepository extends JpaRepository<HourlyGateDecisionEntity, Long> {

    Optional<HourlyGateDecisionEntity> findTopByOrderByTradingDateDescGateTimeDescCreatedAtDesc();

    List<HourlyGateDecisionEntity> findAllByOrderByTradingDateDescGateTimeDescCreatedAtDesc(Pageable pageable);
}
