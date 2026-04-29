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

    /**
     * 找出指定 symbol 在指定 status 下、依 entry_date asc + id asc 排序的所有 paper trades。
     * <p>P0.3：PositionReview EXIT 自動平倉用來找對應的 OPEN paper_trade。</p>
     */
    List<PaperTradeEntity> findBySymbolAndStatusOrderByEntryDateAscIdAsc(String symbol, String status);

    /** 已平倉清單,按 exit_date 倒序;用於 /closed API 與 KPI 計算。 */
    List<PaperTradeEntity> findByStatusAndExitDateBetweenOrderByExitDateDescIdDesc(
            String status, LocalDate from, LocalDate to);

    List<PaperTradeEntity> findByStatusAndExitDateGreaterThanEqualOrderByExitDateDescIdDesc(
            String status, LocalDate from);

    /** 僅依進場日篩選,用於 dashboard /closed?from&to 補單日查詢。 */
    List<PaperTradeEntity> findByEntryDateBetweenOrderByEntryDateDescIdDesc(LocalDate from, LocalDate to);

    /** P0.6：找指定 entry_date 的所有 trade（不論 status / shadow），給 BackfillReturnsJob 用。 */
    List<PaperTradeEntity> findByEntryDate(LocalDate entryDate);
}
