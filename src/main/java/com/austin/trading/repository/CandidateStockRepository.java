package com.austin.trading.repository;

import com.austin.trading.entity.CandidateStockEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CandidateStockRepository extends JpaRepository<CandidateStockEntity, Long> {

    List<CandidateStockEntity> findByTradingDateOrderByScoreDesc(LocalDate tradingDate, Pageable pageable);

    List<CandidateStockEntity> findAllByOrderByTradingDateDescScoreDesc(Pageable pageable);

    Optional<CandidateStockEntity> findByTradingDateAndSymbol(LocalDate tradingDate, String symbol);

    /** 取得最新一筆（用於推算最後有效交易日） */
    Optional<CandidateStockEntity> findTopByOrderByTradingDateDesc();

    /** 按日期刪除（admin cleanup 用） */
    long deleteByTradingDate(LocalDate tradingDate);
}
