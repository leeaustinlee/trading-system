package com.austin.trading.repository;

import com.austin.trading.entity.FinalDecisionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FinalDecisionRepository extends JpaRepository<FinalDecisionEntity, Long> {

    Optional<FinalDecisionEntity> findTopByOrderByTradingDateDescCreatedAtDesc();

    List<FinalDecisionEntity> findAllByOrderByTradingDateDescCreatedAtDesc(Pageable pageable);

    /** Today's most recent FinalDecision (used by REVERSE_SIGNAL trigger). */
    Optional<FinalDecisionEntity> findTopByTradingDateOrderByCreatedAtDesc(LocalDate tradingDate);
}
