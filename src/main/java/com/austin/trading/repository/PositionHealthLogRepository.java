package com.austin.trading.repository;

import com.austin.trading.entity.PositionHealthLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PositionHealthLogRepository extends JpaRepository<PositionHealthLogEntity, Long> {
}
