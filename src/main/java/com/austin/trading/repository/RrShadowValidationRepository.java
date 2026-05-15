package com.austin.trading.repository;

import com.austin.trading.entity.RrShadowValidationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RrShadowValidationRepository extends JpaRepository<RrShadowValidationEntity, Long> {
    Optional<RrShadowValidationEntity> findByPaperTradeId(Long paperTradeId);
    Optional<RrShadowValidationEntity> findBySourceForwardTrackingId(Long sourceForwardTrackingId);
    List<RrShadowValidationEntity> findByTradingDateBetweenOrderByTradingDateAscIdAsc(LocalDate start, LocalDate end);
    long countByTradingDateBetween(LocalDate start, LocalDate end);
}
