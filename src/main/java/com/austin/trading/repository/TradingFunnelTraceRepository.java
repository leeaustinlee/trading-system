package com.austin.trading.repository;

import com.austin.trading.domain.enums.TradingFunnelBlockedStage;
import com.austin.trading.entity.TradingFunnelTraceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Repository for P0 read-only Trading Funnel Shadow Trace rows. */
public interface TradingFunnelTraceRepository extends JpaRepository<TradingFunnelTraceEntity, Long> {
    List<TradingFunnelTraceEntity> findByTradingDate(LocalDate tradingDate);
    List<TradingFunnelTraceEntity> findByTradingDateBetween(LocalDate startDate, LocalDate endDate);
    List<TradingFunnelTraceEntity> findBySymbolAndTradingDateBetweenOrderByTradingDateDesc(String symbol, LocalDate startDate, LocalDate endDate);
    List<TradingFunnelTraceEntity> findByTradingDateAndTraceStatus(LocalDate tradingDate, String traceStatus);
    Optional<TradingFunnelTraceEntity> findByTradingDateAndSymbolAndSignalId(LocalDate tradingDate, String symbol, Long signalId);
    Optional<TradingFunnelTraceEntity> findTopByTradingDateAndSymbolOrderByUpdatedAtDesc(LocalDate tradingDate, String symbol);
    List<TradingFunnelTraceEntity> findByTradingDateAndThemeTag(LocalDate tradingDate, String themeTag);
    List<TradingFunnelTraceEntity> findByTradingDateAndBlockedStage(LocalDate tradingDate, TradingFunnelBlockedStage blockedStage);
}
