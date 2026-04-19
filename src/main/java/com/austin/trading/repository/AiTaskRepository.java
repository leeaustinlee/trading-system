package com.austin.trading.repository;

import com.austin.trading.entity.AiTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AiTaskRepository extends JpaRepository<AiTaskEntity, Long> {

    /**
     * 以 UNIQUE KEY (trading_date, task_type, target_symbol) 查詢。
     * 注意 MySQL 允許 UNIQUE 欄位中多筆 null — 此方法對 target_symbol=null 不適用於唯一性判斷，
     * 只用於「target_symbol 有值」的情況。
     */
    Optional<AiTaskEntity> findByTradingDateAndTaskTypeAndTargetSymbol(
            LocalDate tradingDate, String taskType, String targetSymbol);

    /** 查找 target_symbol IS NULL 的任務（市場層級任務：PREMARKET/POSTMARKET 等） */
    Optional<AiTaskEntity> findByTradingDateAndTaskTypeAndTargetSymbolIsNull(
            LocalDate tradingDate, String taskType);

    /** AI 認領用：找最舊的 PENDING 任務。 */
    List<AiTaskEntity> findByStatusAndTaskTypeOrderByCreatedAtAsc(String status, String taskType);

    /** 查全部待處理任務（跨類型）。 */
    List<AiTaskEntity> findByStatusOrderByCreatedAtAsc(String status);

    List<AiTaskEntity> findByTradingDateOrderByCreatedAtDesc(LocalDate tradingDate);

    List<AiTaskEntity> findByTradingDateAndTaskType(LocalDate tradingDate, String taskType);
}
