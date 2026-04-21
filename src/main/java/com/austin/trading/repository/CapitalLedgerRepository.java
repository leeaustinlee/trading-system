package com.austin.trading.repository;

import com.austin.trading.entity.CapitalLedgerEntity;
import com.austin.trading.entity.LedgerType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface CapitalLedgerRepository extends JpaRepository<CapitalLedgerEntity, Long> {

    /** 累計現金餘額 = SUM(amount)；空表回傳 null 由 caller 轉 ZERO */
    @Query("SELECT SUM(l.amount) FROM CapitalLedgerEntity l")
    BigDecimal sumAllAmount();

    /** 指定區間內 ledger type 的加總（用於對帳；如：今日 FEE 合計）*/
    @Query("SELECT COALESCE(SUM(l.amount), 0) FROM CapitalLedgerEntity l " +
           "WHERE l.ledgerType = :type " +
           "AND l.occurredAt >= :from AND l.occurredAt < :to")
    BigDecimal sumByTypeBetween(
            @Param("type") LedgerType type,
            @Param("from") LocalDateTime from,
            @Param("to")   LocalDateTime to);

    /** 指定交易日的 realized pnl 推導用：SUM(SELL_* + FEE + TAX + BUY_OPEN 中這些 position 的) 過於複雜，
     *  現階段改用 DailyPnl + position.realizedPnl 既有邏輯，這裡只提供 ledger 原貌查詢。*/
    List<CapitalLedgerEntity> findByPositionIdOrderByOccurredAtAsc(Long positionId);

    List<CapitalLedgerEntity> findAllByOrderByOccurredAtDescIdDesc(Pageable pageable);

    @Query("SELECT l FROM CapitalLedgerEntity l " +
           "WHERE (:type IS NULL OR l.ledgerType = :type) " +
           "AND (:symbol IS NULL OR l.symbol = :symbol) " +
           "AND (:from IS NULL OR l.tradeDate >= :from) " +
           "AND (:to   IS NULL OR l.tradeDate <= :to) " +
           "ORDER BY l.occurredAt DESC, l.id DESC")
    List<CapitalLedgerEntity> findByFilter(
            @Param("type")   LedgerType type,
            @Param("symbol") String     symbol,
            @Param("from")   LocalDate  from,
            @Param("to")     LocalDate  to,
            Pageable pageable);
}
