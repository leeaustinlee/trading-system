package com.austin.trading.repository;

import com.austin.trading.entity.PaperTradeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PaperTradeRepository extends JpaRepository<PaperTradeEntity, Long> {

    Optional<PaperTradeEntity> findByTradeId(String tradeId);

    List<PaperTradeEntity> findByStatusOrderByEntryDateAscIdAsc(String status);

    List<PaperTradeEntity> findByEntryDateAndSymbol(LocalDate entryDate, String symbol);

    /** 已平倉清單,按 exit_date 倒序;用於 /closed API 與 KPI 計算。 */
    List<PaperTradeEntity> findByStatusAndExitDateBetweenOrderByExitDateDescIdDesc(
            String status, LocalDate from, LocalDate to);

    List<PaperTradeEntity> findByStatusAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc(
            String status, LocalDate from);

    /** 僅依進場日篩選,用於 dashboard /closed?from&to 補單日查詢。 */
    List<PaperTradeEntity> findByEntryDateBetweenOrderByEntryDateDescIdDesc(LocalDate from, LocalDate to);
}
