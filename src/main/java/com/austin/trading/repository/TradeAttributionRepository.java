package com.austin.trading.repository;

import com.austin.trading.entity.TradeAttributionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TradeAttributionRepository
        extends JpaRepository<TradeAttributionEntity, Long> {

    Optional<TradeAttributionEntity> findByPositionId(Long positionId);

    List<TradeAttributionEntity> findBySymbolOrderByEntryDateDesc(String symbol);

    /** Weekly learning aggregate feed — all attributions grouped by key dimensions. */
    @Query("SELECT a FROM TradeAttributionEntity a ORDER BY a.entryDate DESC")
    List<TradeAttributionEntity> findAllOrderByEntryDateDesc();
}
