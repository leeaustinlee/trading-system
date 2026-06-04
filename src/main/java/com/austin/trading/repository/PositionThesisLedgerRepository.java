package com.austin.trading.repository;

import com.austin.trading.entity.PositionThesisLedgerEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PositionThesisLedgerRepository extends JpaRepository<PositionThesisLedgerEntity, Long> {
    List<PositionThesisLedgerEntity> findByOpenPositionTrueOrderByLatestReviewDateDescIdDesc();

    Optional<PositionThesisLedgerEntity> findTopBySymbolAndOpenPositionTrueOrderByLatestReviewDateDescIdDesc(String symbol);
}
