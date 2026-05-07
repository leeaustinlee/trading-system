package com.austin.trading.repository;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Map;
import java.time.LocalDate;

public interface CandidateForwardTrackingRepository extends JpaRepository<CandidateForwardTrackingEntity, Long> {
    List<CandidateForwardTrackingEntity> findByTradingDateBetween(LocalDate start, LocalDate end);
    List<CandidateForwardTrackingEntity> findByTradingDateGreaterThanEqual(LocalDate start);

    @Query("select new map(coalesce(c.finalDecision,'UNKNOWN') as name, count(c) as total, avg(c.t5CloseReturnPct) as avgT5) from CandidateForwardTrackingEntity c group by coalesce(c.finalDecision,'UNKNOWN')")
    List<Map<String, Object>> byDecision();
    @Query("select new map(coalesce(c.grade,'UNKNOWN') as name, count(c) as total, avg(c.t5CloseReturnPct) as avgT5) from CandidateForwardTrackingEntity c group by coalesce(c.grade,'UNKNOWN')")
    List<Map<String, Object>> byGrade();
    @Query("select new map(coalesce(c.primaryStrategy,'UNKNOWN') as name, count(c) as total, avg(c.t5CloseReturnPct) as avgT5) from CandidateForwardTrackingEntity c group by coalesce(c.primaryStrategy,'UNKNOWN')")
    List<Map<String, Object>> byStrategy();
    @Query("select new map(coalesce(c.gateName,'UNKNOWN') as name, count(c) as total, avg(c.t5CloseReturnPct) as avgT5) from CandidateForwardTrackingEntity c group by coalesce(c.gateName,'UNKNOWN')")
    List<Map<String, Object>> byGate();
}
