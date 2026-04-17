package com.austin.trading.repository;

import com.austin.trading.entity.DailyPnlEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface DailyPnlRepository extends JpaRepository<DailyPnlEntity, Long> {

    Optional<DailyPnlEntity> findTopByOrderByTradingDateDescCreatedAtDesc();

    List<DailyPnlEntity> findAllByOrderByTradingDateDescCreatedAtDesc(Pageable pageable);

    @Query("SELECT p FROM DailyPnlEntity p WHERE " +
           "(:from IS NULL OR p.tradingDate >= :from) " +
           "AND (:to IS NULL OR p.tradingDate <= :to) " +
           "ORDER BY p.tradingDate DESC")
    List<DailyPnlEntity> findByDateRange(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            Pageable pageable);
}
