package com.austin.trading.repository;

import com.austin.trading.entity.MarketSnapshotEntity;
import org.springframework.data.domain.Pageable;
import java.util.Optional;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MarketSnapshotRepository extends JpaRepository<MarketSnapshotEntity, Long> {

    Optional<MarketSnapshotEntity> findTopByOrderByTradingDateDescCreatedAtDesc();

    List<MarketSnapshotEntity> findAllByOrderByTradingDateDescCreatedAtDesc(Pageable pageable);
}
