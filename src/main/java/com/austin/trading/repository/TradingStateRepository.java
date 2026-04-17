package com.austin.trading.repository;

import com.austin.trading.entity.TradingStateEntity;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradingStateRepository extends JpaRepository<TradingStateEntity, Long> {

    Optional<TradingStateEntity> findTopByOrderByTradingDateDescUpdatedAtDesc();

    List<TradingStateEntity> findAllByOrderByTradingDateDescUpdatedAtDesc(Pageable pageable);
}
