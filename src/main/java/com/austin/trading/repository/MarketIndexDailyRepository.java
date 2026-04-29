package com.austin.trading.repository;

import com.austin.trading.entity.MarketIndexDailyEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 歷史日線 repository — 為 {@code RealDowngradeEvaluator} 三個 trigger 與
 * P0.5 backfill / DataPrep job 提供 CRUD + 反向時間排序查詢。
 *
 * <p>大部分查詢需要「最近 N 個交易日」，因此 native ordering 走
 * {@code trading_date DESC}；evaluator 端拿到後自行反轉成 oldest-first。</p>
 */
public interface MarketIndexDailyRepository extends JpaRepository<MarketIndexDailyEntity, Long> {

    /**
     * 取得指定 symbol 在 {@code [from, to]} 區間內的所有日線（含端點），
     * 由舊到新排序，方便直接餵 evaluator。
     */
    List<MarketIndexDailyEntity> findBySymbolAndTradingDateBetweenOrderByTradingDateAsc(
            String symbol, LocalDate from, LocalDate to);

    /**
     * 取得 symbol 最新 N 筆日線（含 {@code asOf} 當日及之前），新到舊排序。
     * Caller 需自行 {@code Collections.reverse} 取得 oldest-first。
     *
     * <p>Spring Data JPA 沒有直接的 LIMIT 語法，這裡用 {@link Pageable} 控制。</p>
     */
    @Query("""
            SELECT m FROM MarketIndexDailyEntity m
             WHERE m.symbol = :symbol
               AND m.tradingDate <= :asOf
             ORDER BY m.tradingDate DESC
            """)
    List<MarketIndexDailyEntity> findLatestBySymbolBefore(
            @Param("symbol") String symbol,
            @Param("asOf") LocalDate asOf,
            Pageable pageable);

    /**
     * 用於 startup backfill 判斷：DB 內某 symbol 共有多少筆？
     * < 30 視為「歷史不足，啟動時自動 backfill」。
     */
    long countBySymbol(String symbol);

    /** 用於 upsert：先 lookup 再決定是 INSERT 或更新。 */
    Optional<MarketIndexDailyEntity> findBySymbolAndTradingDate(String symbol, LocalDate tradingDate);
}
