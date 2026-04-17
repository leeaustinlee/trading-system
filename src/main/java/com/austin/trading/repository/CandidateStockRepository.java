package com.austin.trading.repository;

import com.austin.trading.entity.CandidateStockEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CandidateStockRepository extends JpaRepository<CandidateStockEntity, Long> {

    List<CandidateStockEntity> findByTradingDateOrderByScoreDesc(LocalDate tradingDate, Pageable pageable);

    List<CandidateStockEntity> findAllByOrderByTradingDateDescScoreDesc(Pageable pageable);
}
