package com.austin.trading.repository;

import com.austin.trading.entity.MissedRallyTrackingEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Map;

public interface MissedRallyTrackingRepository extends JpaRepository<MissedRallyTrackingEntity, Long> {
    List<MissedRallyTrackingEntity> findAllByOrderByTradingDateDescIdDesc(Pageable pageable);

    @Query("select new map(coalesce(m.gateName,'UNKNOWN') as name, count(m) as total, sum(case when m.missedRallyFlag = true then 1 else 0 end) as missed) from MissedRallyTrackingEntity m group by coalesce(m.gateName,'UNKNOWN')")
    List<Map<String, Object>> summaryByGate();

    @Query("select new map(coalesce(m.primaryStrategy,'UNKNOWN') as name, count(m) as total, sum(case when m.missedRallyFlag = true then 1 else 0 end) as missed) from MissedRallyTrackingEntity m group by coalesce(m.primaryStrategy,'UNKNOWN')")
    List<Map<String, Object>> summaryByStrategy();
}
