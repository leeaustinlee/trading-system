package com.austin.trading.repository;

import com.austin.trading.entity.NarrativeShadowReviewEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface NarrativeShadowReviewRepository extends JpaRepository<NarrativeShadowReviewEntity, Long> {
    Optional<NarrativeShadowReviewEntity> findByTradingDate(LocalDate tradingDate);
    long deleteByTradingDate(LocalDate tradingDate);
}
