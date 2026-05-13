package com.austin.trading.repository;

import com.austin.trading.entity.KolThemeStockMappingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface KolThemeStockMappingRepository extends JpaRepository<KolThemeStockMappingEntity, Long> {
    List<KolThemeStockMappingEntity> findBySignalId(Long signalId);
    List<KolThemeStockMappingEntity> findByTradingDateAndThemeTag(LocalDate tradingDate, String themeTag);
    Optional<KolThemeStockMappingEntity> findBySignalIdAndThemeTagAndSymbol(Long signalId, String themeTag, String symbol);
    long countBySignalId(Long signalId);
    long deleteBySignalId(Long signalId);
}
