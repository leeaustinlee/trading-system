package com.austin.trading.repository;

import com.austin.trading.entity.WatchlistStockEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchlistStockRepository extends JpaRepository<WatchlistStockEntity, Long> {

    Optional<WatchlistStockEntity> findBySymbol(String symbol);

    List<WatchlistStockEntity> findByWatchStatusInOrderByCurrentScoreDesc(List<String> statuses);

    List<WatchlistStockEntity> findByWatchStatusOrderByConsecutiveStrongDaysDescCurrentScoreDesc(String status);

    List<WatchlistStockEntity> findAllByOrderByUpdatedAtDesc();
}
