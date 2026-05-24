package com.austin.trading.repository;

import com.austin.trading.entity.SystemBuildTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SystemBuildTraceRepository extends JpaRepository<SystemBuildTraceEntity, Long> {
    List<SystemBuildTraceEntity> findByTradingDateOrderByStartedAtDescIdDesc(LocalDate tradingDate);
    List<SystemBuildTraceEntity> findByTradingDateAndBuildTypeOrderByStartedAtDescIdDesc(LocalDate tradingDate, String buildType);
}
