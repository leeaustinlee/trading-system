package com.austin.trading.repository;

import com.austin.trading.entity.DailyOrchestrationStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyOrchestrationStatusRepository
        extends JpaRepository<DailyOrchestrationStatusEntity, LocalDate> {

    Optional<DailyOrchestrationStatusEntity> findByTradingDate(LocalDate tradingDate);

    /**
     * 取指定日期記錄並加悲觀寫鎖，用於 {@code markRunning} 的原子更新流程，
     * 避免 scheduler 與手動觸發同時呼叫時出現併發問題。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DailyOrchestrationStatusEntity d WHERE d.tradingDate = :date")
    Optional<DailyOrchestrationStatusEntity> findForUpdate(@Param("date") LocalDate tradingDate);

    List<DailyOrchestrationStatusEntity> findTop5ByOrderByTradingDateDesc();
}
