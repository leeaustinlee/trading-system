package com.austin.trading.repository;

import com.austin.trading.entity.TradingStateEntity;
import org.springframework.data.domain.Pageable;
import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradingStateRepository extends JpaRepository<TradingStateEntity, Long> {

    Optional<TradingStateEntity> findTopByOrderByTradingDateDescUpdatedAtDesc();

    /** v2.4：只撈指定日期的最新 state，不跨日污染。 */
    Optional<TradingStateEntity> findTopByTradingDateOrderByUpdatedAtDesc(LocalDate tradingDate);

    List<TradingStateEntity> findAllByOrderByTradingDateDescUpdatedAtDesc(Pageable pageable);
}
