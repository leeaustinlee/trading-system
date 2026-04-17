package com.austin.trading.repository;

import com.austin.trading.entity.PositionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface PositionRepository extends JpaRepository<PositionEntity, Long> {

    List<PositionEntity> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    List<PositionEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT p FROM PositionEntity p WHERE p.status = :status " +
           "AND (:symbol IS NULL OR p.symbol = :symbol) " +
           "ORDER BY p.createdAt DESC")
    List<PositionEntity> findOpenByFilter(
            @Param("status") String status,
            @Param("symbol") String symbol,
            Pageable pageable);

    @Query("SELECT p FROM PositionEntity p WHERE " +
           "(:symbol IS NULL OR p.symbol = :symbol) " +
           "AND (:from IS NULL OR p.openedAt >= :from) " +
           "AND (:to IS NULL OR p.openedAt <= :to) " +
           "ORDER BY p.openedAt DESC")
    List<PositionEntity> findHistoryByFilter(
            @Param("symbol") String symbol,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to,
            Pageable pageable);

    /**
     * 查詢指定時間區間內關閉的持倉，用於當日損益稽核。
     */
    @Query("SELECT p FROM PositionEntity p WHERE p.status = 'CLOSED' " +
           "AND p.closedAt >= :start AND p.closedAt < :end " +
           "ORDER BY p.closedAt DESC")
    List<PositionEntity> findClosedBetween(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);

    /**
     * 計算指定時間區間內已實現損益加總（無已關閉持倉時返回 null）。
     */
    @Query("SELECT SUM(p.realizedPnl) FROM PositionEntity p WHERE p.status = 'CLOSED' " +
           "AND p.closedAt >= :start AND p.closedAt < :end")
    BigDecimal sumRealizedPnlBetween(
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
